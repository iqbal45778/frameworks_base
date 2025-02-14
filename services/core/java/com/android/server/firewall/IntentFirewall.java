/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.firewall;

import android.annotation.NonNull;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;
import com.android.server.EventLogTags;
import com.android.server.IntentResolver;
import com.android.server.LocalServices;
import com.android.server.pm.Computer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class IntentFirewall {
    static final String TAG = "IntentFirewall";

    // e.g. /data/system/ifw or /data/secure/system/ifw
    private static final File RULES_DIR = new File(Environment.getDataSystemDirectory(), "ifw");
    // not observed for changes, not created if missing, but can load rules from there on boot
    private static final String[] SECONDARY_RULE_DIRS = new String[] {
        "/system/etc/ifw.d/",
        "/system_ext/etc/ifw.d/",
        "/product/etc/ifw.d/",
        "/odm/etc/ifw.d/",
        "/vendor/etc/ifw.d/",
    };

    private static final int LOG_PACKAGES_MAX_LENGTH = 150;
    private static final int LOG_PACKAGES_SUFFICIENT_LENGTH = 125;

    private static final String TAG_RULES = "rules";
    private static final String TAG_ACTIVITY = "activity";
    private static final String TAG_SERVICE = "service";
    private static final String TAG_BROADCAST = "broadcast";
    private static final String TAG_PROVIDER = "provider";
    private static final String TAG_PACKAGE = "package";

    private static final int TYPE_ACTIVITY = 0;
    private static final int TYPE_BROADCAST = 1;
    private static final int TYPE_SERVICE = 2;
    private static final int TYPE_PROVIDER = 3;
    private static final int TYPE_PACKAGE = 4;

    private static final HashMap<String, FilterFactory> factoryMap;

    private final AMSInterface mAms;

    private final RuleObserver mObserver;

    @NonNull
    private PackageManagerInternal mPackageManager;

    private FirewallIntentResolver mActivityResolver = new FirewallIntentResolver();
    private FirewallIntentResolver mBroadcastResolver = new FirewallIntentResolver();
    private FirewallIntentResolver mServiceResolver = new FirewallIntentResolver();
    private FirewallIntentResolver mProviderResolver = new FirewallIntentResolver();
    private List<Rule> mPackageResolver = new ArrayList<>();

    static {
        FilterFactory[] factories = new FilterFactory[] {
                AndFilter.FACTORY,
                OrFilter.FACTORY,
                NotFilter.FACTORY,

                StringFilter.ACTION,
                StringFilter.COMPONENT,
                StringFilter.COMPONENT_NAME,
                StringFilter.COMPONENT_PACKAGE,
                StringFilter.DATA,
                StringFilter.HOST,
                StringFilter.MIME_TYPE,
                StringFilter.SCHEME,
                StringFilter.PATH,
                StringFilter.SSP,

                CategoryFilter.FACTORY,
                SenderFilter.FACTORY,
                SenderPackageFilter.FACTORY,
                SenderPermissionFilter.FACTORY,
                TargetFilter.FACTORY,
                TargetPackageFilter.FACTORY,
                TargetPermissionFilter.FACTORY,
                PortFilter.FACTORY,
                IntentFilterFilter.FACTORY,
                ComponentFilter.FACTORY,
                ProvisionedFilter.FACTORY
        };

        // load factor ~= .75
        factoryMap = new HashMap<String, FilterFactory>(factories.length * 4 / 3);
        for (int i=0; i<factories.length; i++) {
            FilterFactory factory = factories[i];
            factoryMap.put(factory.getTagName(), factory);
        }
    }

    public IntentFirewall(AMSInterface ams, Handler handler) {
        mAms = ams;
        mHandler = new FirewallHandler(handler.getLooper());
        File rulesDir = getRulesDir();
        rulesDir.mkdirs();

        readRulesDir(rulesDir);

        mObserver = new RuleObserver(rulesDir);
        mObserver.startWatching();
    }

    PackageManagerInternal getPackageManager() {
        if (mPackageManager == null) {
            mPackageManager = LocalServices.getService(PackageManagerInternal.class);
        }
        return mPackageManager;
    }

    /**
     * This is called from ActivityManager to check if a start activity intent should be allowed.
     * It is assumed the caller is already holding the global ActivityManagerService lock.
     */
    public boolean checkStartActivity(Intent intent, int callerUid, int callerPid,
            String resolvedType, ApplicationInfo resolvedApp, int userId) {
        return checkIntent(mActivityResolver, intent.getComponent(), TYPE_ACTIVITY, intent,
                callerUid, callerPid, resolvedType, resolvedApp.uid, false, userId);
    }

    public boolean checkService(ComponentName resolvedService, Intent intent, int callerUid,
            int callerPid, String resolvedType, ApplicationInfo resolvedApp, int userId) {
        return checkIntent(mServiceResolver, resolvedService, TYPE_SERVICE, intent, callerUid,
                callerPid, resolvedType, resolvedApp.uid, false, userId);
    }

    public boolean checkBroadcast(Intent intent, int callerUid, int callerPid,
            String resolvedType, int receivingUid, int userId) {
        return checkIntent(mBroadcastResolver, intent.getComponent(), TYPE_BROADCAST, intent,
                callerUid, callerPid, resolvedType, receivingUid, false, userId);
    }

    public boolean checkProvider(ComponentName resolvedProvider, Intent intent, int callerUid,
                                     int callerPid, String resolvedType, ApplicationInfo resolvedApp, int userId) {
        return checkIntent(mProviderResolver, resolvedProvider, TYPE_PROVIDER, intent, callerUid,
                callerPid, resolvedType, resolvedApp.uid, false, userId);
    }

    public boolean checkQueryActivity(ComponentName resolvedActivity, Intent intent, int callerUid, int callerPid,
            String resolvedType, ApplicationInfo resolvedApp, int userId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return checkIntent(mActivityResolver, resolvedActivity, TYPE_ACTIVITY, intent,
                    callerUid, callerPid, resolvedType, resolvedApp.uid, true, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean checkQueryService(ComponentName resolvedService, Intent intent, int callerUid,
            int callerPid, String resolvedType, ApplicationInfo resolvedApp, int userId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return checkIntent(mServiceResolver, resolvedService, TYPE_SERVICE, intent, callerUid,
                    callerPid, resolvedType, resolvedApp.uid, true, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean checkQueryReceiver(ComponentName resolvedReceiver, Intent intent, int callerUid,
            int callerPid, String resolvedType, ApplicationInfo resolvedApp, int userId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return checkIntent(mBroadcastResolver, resolvedReceiver, TYPE_BROADCAST, intent, callerUid,
                    callerPid, resolvedType, resolvedApp.uid, true, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean checkQueryProvider(ComponentName resolvedService, Intent intent, int callerUid,
            int callerPid, String resolvedType, ApplicationInfo resolvedApp, int userId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return checkIntent(mProviderResolver, resolvedService, TYPE_PROVIDER, intent, callerUid,
                    callerPid, resolvedType, resolvedApp.uid, true, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean checkQueryPackage(int targetUid, String targetPackageName, int callerUid, int userId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            boolean log = false;
            boolean block = false;
            for (Rule rule : mPackageResolver) {
                if (rule.matchesPackage(this, targetPackageName, callerUid, targetUid, userId)) {
                    block |= rule.getUnqueryable();
                    log |= rule.getLogQuery();

                    // if we've already determined that we should both block and log, there's no need
                    // to continue trying rules
                    if (block && log) {
                        break;
                    }
                }
            }

            if (log) {
                logPackageQuery(targetUid, targetPackageName, callerUid, userId);
            }
            return !block;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean checkIntent(FirewallIntentResolver resolver, ComponentName resolvedComponent,
            int intentType, Intent intent, int callerUid, int callerPid, String resolvedType,
            int receivingUid, boolean forQuery, int userId) {
        // For the first pass, find all the rules that have at least one intent-filter or
        // component-filter that matches this intent
        List<Rule> candidateRules = null;
        if (intent != null) {
            candidateRules = resolver.queryIntent(getPackageManager().snapshot(), intent, resolvedType,
                    false /*defaultOnly*/, 0);
        }
        if (candidateRules == null) {
            candidateRules = new ArrayList<>();
        }
        resolver.queryByComponent(resolvedComponent, candidateRules);
        resolver.addAllMatching(candidateRules);

        // For the second pass, try to match the potentially more specific conditions in each
        // rule against the intent
        boolean log = false;
        boolean block = false;
        for (int i=0; i<candidateRules.size(); i++) {
            Rule rule = candidateRules.get(i);
            if (rule.matches(this, resolvedComponent, intent, callerUid, callerPid, resolvedType,
                    receivingUid, userId)) {
                block |= (forQuery ? rule.getUnqueryable() : rule.getBlock());
                log |= (forQuery ? rule.getLogQuery() : rule.getLog());

                // if we've already determined that we should both block and log, there's no need
                // to continue trying rules
                if (block && log) {
                    break;
                }
            }
        }

        if (log) {
            logIntent(intentType, intent, resolvedComponent, callerUid, resolvedType);
        }

        return !block;
    }

    private static void logIntent(int intentType, Intent intent, ComponentName bcn, int callerUid,
            String resolvedType) {
        // The component shouldn't be null, but let's double check just to be safe
        ComponentName cn;
        // We want to log caller's information provided to system, not what is resolved
        if (intent != null) {
            cn = intent.getComponent();
        } else {
            cn = bcn;
        }
        String shortComponent = null;
        if (cn != null) {
            shortComponent = cn.flattenToShortString();
        }

        String callerPackages = null;
        int callerPackageCount = 0;
        IPackageManager pm = AppGlobals.getPackageManager();
        if (pm != null) {
            try {
                String[] callerPackagesArray = pm.getPackagesForUid(callerUid);
                if (callerPackagesArray != null) {
                    callerPackageCount = callerPackagesArray.length;
                    callerPackages = joinPackages(callerPackagesArray);
                }
            } catch (RemoteException ex) {
                Slog.e(TAG, "Remote exception while retrieving packages", ex);
            }
        }

        String action = intent != null ? intent.getAction() : null;
        String dataString = intent != null ? intent.getDataString() : null;
        int flags = intent != null ? intent.getFlags() : 0;
        EventLogTags.writeIfwIntentMatched(intentType, shortComponent, callerUid,
                callerPackageCount, callerPackages, action, resolvedType, dataString, flags);
    }

    private static void logPackageQuery(int targetUid, String targetPackageName, int callerUid, int userId) {
        Slog.d(TAG, "IFW package query log action triggered: targetUid=" + targetUid + " pkgName=" +
                targetPackageName + " callerUid=" + callerUid + " userId=" + userId);
    }

    /**
     * Joins a list of package names such that the resulting string is no more than
     * LOG_PACKAGES_MAX_LENGTH.
     *
     * Only full package names will be added to the result, unless every package is longer than the
     * limit, in which case one of the packages will be truncated and added. In this case, an
     * additional '-' character will be added to the end of the string, to denote the truncation.
     *
     * If it encounters a package that won't fit in the remaining space, it will continue on to the
     * next package, unless the total length of the built string so far is greater than
     * LOG_PACKAGES_SUFFICIENT_LENGTH, in which case it will stop and return what it has.
     */
    private static String joinPackages(String[] packages) {
        boolean first = true;
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<packages.length; i++) {
            String pkg = packages[i];

            if (sb.length() + pkg.length() + (first ? 0 : 1) < LOG_PACKAGES_MAX_LENGTH) {
                if (!first) {
                    sb.append(',');
                } else {
                    first = false;
                }
                sb.append(pkg);
            } else if (sb.length() >= LOG_PACKAGES_SUFFICIENT_LENGTH) {
                return sb.toString();
            }
        }
        if (sb.length() == 0 && packages.length > 0) {
            String pkg = packages[0];
            // truncating from the end - the last part of the package name is more likely to be
            // interesting/unique
            return pkg.substring(pkg.length() - LOG_PACKAGES_MAX_LENGTH + 1) + '-';
        }
        return null;
    }

    public static File getRulesDir() {
        return RULES_DIR;
    }

    ContentResolver getContentResolver() {
        return mAms.getContentResolver();
    }

    /**
     * Reads rules from all xml files (*.xml) in the given directory, and replaces our set of rules
     * with the newly read rules.
     *
     * We only check for files ending in ".xml", to allow for temporary files that are atomically
     * renamed to .xml
     *
     * All calls to this method from the file observer come through a handler and are inherently
     * serialized
     */
    private void readRulesDir(File rulesDir) {
        FirewallIntentResolver[] resolvers = new FirewallIntentResolver[4];
        for (int i=0; i<resolvers.length; i++) {
            resolvers[i] = new FirewallIntentResolver();
        }
        List<Rule> pkgResolver = new ArrayList<>();

        final ArrayList<File> allFiles = new ArrayList<>();
        if (rulesDir != null) {
            File[] files = rulesDir.listFiles();
            if (files != null) {
                Collections.addAll(allFiles, files);
            }
        }
        for (String path : SECONDARY_RULE_DIRS) {
            File[] files = new File(path).listFiles();
            if (files != null) {
                Collections.addAll(allFiles, files);
            }
        }
        for (File file : allFiles) {
            if (file.getName().endsWith(".xml")) {
                readRules(file, resolvers, pkgResolver);
            }
        }

        Slog.i(TAG, "Read new rules (A:" + resolvers[TYPE_ACTIVITY].size() +
                " B:" + resolvers[TYPE_BROADCAST].size() +
                " S:" + resolvers[TYPE_SERVICE].size() +
                " C:" + resolvers[TYPE_PROVIDER].size() +
                " P:" + pkgResolver.size() + ")");

        synchronized (mAms.getAMSLock()) {
            mActivityResolver = resolvers[TYPE_ACTIVITY];
            mBroadcastResolver = resolvers[TYPE_BROADCAST];
            mServiceResolver = resolvers[TYPE_SERVICE];
            mProviderResolver = resolvers[TYPE_PROVIDER];
            mPackageResolver = pkgResolver;
        }
    }

    /**
     * Reads rules from the given file and add them to the given resolvers
     */
    private void readRules(File rulesFile, FirewallIntentResolver[] resolvers, List<Rule> pkgResolver) {
        // some temporary lists to hold the rules while we parse the xml file, so that we can
        // add the rules all at once, after we know there weren't any major structural problems
        // with the xml file
        List<List<Rule>> rulesByType = new ArrayList<List<Rule>>(5);
        for (int i=0; i<5; i++) {
            rulesByType.add(new ArrayList<Rule>());
        }

        FileInputStream fis;
        try {
            fis = new FileInputStream(rulesFile);
        } catch (FileNotFoundException ex) {
            // Nope, no rules. Nothing else to do!
            return;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();

            parser.setInput(fis, null);

            XmlUtils.beginDocument(parser, TAG_RULES);

            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                int ruleType = -1;

                String tagName = parser.getName();
                if (tagName.equals(TAG_ACTIVITY)) {
                    ruleType = TYPE_ACTIVITY;
                } else if (tagName.equals(TAG_BROADCAST)) {
                    ruleType = TYPE_BROADCAST;
                } else if (tagName.equals(TAG_SERVICE)) {
                    ruleType = TYPE_SERVICE;
                } else if (tagName.equals(TAG_PROVIDER)) {
                    ruleType = TYPE_PROVIDER;
                } else if (tagName.equals(TAG_PACKAGE)) {
                    ruleType = TYPE_PACKAGE;
                }

                Rule rule = new Rule();
                // if we get an error while parsing a particular rule, we'll just ignore
                // that rule and continue on with the next rule
                try {
                    rule.readFromXml(parser);
                } catch (XmlPullParserException ex) {
                    Slog.e(TAG, "Error reading an intent firewall rule from " + rulesFile, ex);
                    continue;
                }

                if (ruleType != -1) {
                    List<Rule> rules = rulesByType.get(ruleType);
                    rules.add(rule);
                }
            }
        } catch (XmlPullParserException ex) {
            // if there was an error outside of a specific rule, then there are probably
            // structural problems with the xml file, and we should completely ignore it
            Slog.e(TAG, "Error reading intent firewall rules from " + rulesFile, ex);
            return;
        } catch (IOException ex) {
            Slog.e(TAG, "Error reading intent firewall rules from " + rulesFile, ex);
            return;
        } finally {
            try {
                fis.close();
            } catch (IOException ex) {
                Slog.e(TAG, "Error while closing " + rulesFile, ex);
            }
        }

        for (int ruleType=0; ruleType<4; ruleType++) {
            List<Rule> rules = rulesByType.get(ruleType);
            FirewallIntentResolver resolver = resolvers[ruleType];

            for (int ruleIndex=0; ruleIndex<rules.size(); ruleIndex++) {
                Rule rule = rules.get(ruleIndex);
                if (rule.matchesAll()) {
                    resolver.addMatchesAll(rule);
                    continue;
                }
                for (int i=0; i<rule.getIntentFilterCount(); i++) {
                    resolver.addFilter(null, rule.getIntentFilter(i));
                }
                for (int i=0; i<rule.getComponentFilterCount(); i++) {
                    resolver.addComponentFilter(rule.getComponentFilter(i), rule);
                }
            }
        }
        pkgResolver.addAll(rulesByType.get(TYPE_PACKAGE));
    }

    static Filter parseFilter(XmlPullParser parser) throws IOException, XmlPullParserException {
        String elementName = parser.getName();

        FilterFactory factory = factoryMap.get(elementName);

        if (factory == null) {
            throw new XmlPullParserException("Unknown element in filter list: " + elementName);
        }
        return factory.newFilter(parser);
    }

    /**
     * Represents a single activity/service/broadcast rule within one of the xml files.
     *
     * Rules are matched against an incoming intent in two phases. The goal of the first phase
     * is to select a subset of rules that might match a given intent.
     *
     * For the first phase, we use a combination of intent filters (via an IntentResolver)
     * and component filters to select which rules to check. If a rule has multiple intent or
     * component filters, only a single filter must match for the rule to be passed on to the
     * second phase.
     *
     * In the second phase, we check the specific conditions in each rule against the values in the
     * intent. All top level conditions (but not filters) in the rule must match for the rule as a
     * whole to match.
     *
     * If the rule matches, then we block or log the intent, as specified by the rule. If multiple
     * rules match, we combine the block/log flags from any matching rule.
     */
    private static class Rule extends AndFilter {
        private static final String TAG_INTENT_FILTER = "intent-filter";
        private static final String TAG_COMPONENT_FILTER = "component-filter";
        private static final String ATTR_NAME = "name";

        private static final String ATTR_PACKAGE_NAME = "pkgName";
        private static final String ATTR_BLOCK = "block";
        private static final String ATTR_LOG = "log";
        private static final String ATTR_LOGQUERY = "logquery";
        private static final String ATTR_MATCH_ALL = "matchall";
        private static final String ATTR_UNQUERYABLE = "blockquery";

        private final ArrayList<FirewallIntentFilter> mIntentFilters =
                new ArrayList<FirewallIntentFilter>(1);
        private final ArrayList<ComponentName> mComponentFilters = new ArrayList<ComponentName>(0);
        private String packageName;
        private boolean block;
        private boolean log;
        private boolean matchall;
        private boolean logquery;
        private boolean unqueryable;

        @Override
        public Rule readFromXml(XmlPullParser parser) throws IOException, XmlPullParserException {
            packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
            block = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_BLOCK));
            log = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_LOG));
            matchall = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_MATCH_ALL));
            logquery = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_LOGQUERY));
            unqueryable = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_UNQUERYABLE));

            super.readFromXml(parser);
            return this;
        }

        @Override
        protected void readChild(XmlPullParser parser) throws IOException, XmlPullParserException {
            String currentTag = parser.getName();

            if (!matchall && currentTag.equals(TAG_INTENT_FILTER)) {
                FirewallIntentFilter intentFilter = new FirewallIntentFilter(this);
                intentFilter.readFromXml(parser);
                mIntentFilters.add(intentFilter);
            } else if (!matchall && currentTag.equals(TAG_COMPONENT_FILTER)) {
                String componentStr = parser.getAttributeValue(null, ATTR_NAME);
                if (componentStr == null) {
                    throw new XmlPullParserException("Component name must be specified.",
                            parser, null);
                }

                ComponentName componentName = ComponentName.unflattenFromString(componentStr);
                if (componentName == null) {
                    throw new XmlPullParserException("Invalid component name: " + componentStr);
                }

                mComponentFilters.add(componentName);
            } else {
                super.readChild(parser);
            }
        }

        @Override
        public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
            int callerUid, int callerPid, String resolvedType, int receivingUid, int userId) {
            if (packageName != null && (resolvedComponent == null
                    || !packageName.equals(resolvedComponent.getPackageName()))) {
                return false;
            }
            return super.matches(ifw, resolvedComponent, intent, callerUid, callerPid, resolvedType,
                    receivingUid, userId);
        }

        @Override
        public boolean matchesPackage(IntentFirewall ifw, String resolvedPackage, int callerUid,
            int receivingUid, int userId) {
            if (packageName != null && !packageName.equals(resolvedPackage)) {
                return false;
            }
            return super.matchesPackage(ifw, resolvedPackage, callerUid, receivingUid, userId);
        }

        public int getIntentFilterCount() {
            return mIntentFilters.size();
        }

        public FirewallIntentFilter getIntentFilter(int index) {
            return mIntentFilters.get(index);
        }

        public int getComponentFilterCount() {
            return mComponentFilters.size();
        }

        public ComponentName getComponentFilter(int index) {
            return mComponentFilters.get(index);
        }

        public boolean matchesAll() {
            return matchall;
        }

        public boolean getBlock() {
            return block;
        }

        public boolean getUnqueryable() {
            return unqueryable;
        }

        public boolean getLog() {
            return log;
        }

        public boolean getLogQuery() {
            return logquery;
        }
    }

    private static class FirewallIntentFilter extends IntentFilter {
        private final Rule rule;

        public FirewallIntentFilter(Rule rule) {
            this.rule = rule;
        }
    }

    private static class FirewallIntentResolver
            extends IntentResolver<FirewallIntentFilter, Rule> {
        @Override
        protected boolean allowFilterResult(FirewallIntentFilter filter, List<Rule> dest) {
            return !dest.contains(filter.rule);
        }

        @Override
        protected boolean isPackageForFilter(String packageName, FirewallIntentFilter filter) {
            return true;
        }

        @Override
        protected FirewallIntentFilter[] newArray(int size) {
            return new FirewallIntentFilter[size];
        }

        @Override
        protected Rule newResult(@NonNull Computer computer, FirewallIntentFilter filter,
                int match, int userId, long customFlags) {
            return filter.rule;
        }

        @Override
        protected void sortResults(List<Rule> results) {
            // there's no need to sort the results
            return;
        }

        @Override
        protected IntentFilter getIntentFilter(@NonNull FirewallIntentFilter input) {
            return input;
        }

        public void queryByComponent(ComponentName componentName, List<Rule> candidateRules) {
            Rule[] rules = mRulesByComponent.get(componentName);
            if (rules != null) {
                candidateRules.addAll(Arrays.asList(rules));
            }
        }

        public void addAllMatching(List<Rule> candidateRules) {
            candidateRules.addAll(mMatchesAll);
        }

        public void addComponentFilter(ComponentName componentName, Rule rule) {
            Rule[] rules = mRulesByComponent.get(componentName);
            rules = ArrayUtils.appendElement(Rule.class, rules, rule);
            mRulesByComponent.put(componentName, rules);
        }

        public void addMatchesAll(Rule rule) {
            mMatchesAll.add(rule);
        }

        public int size() {
            return filterSet().size() + mRulesByComponent.size() + mMatchesAll.size();
        }

        private final ArrayMap<ComponentName, Rule[]> mRulesByComponent =
                new ArrayMap<ComponentName, Rule[]>(0);

        private final ArrayList<Rule> mMatchesAll =
                new ArrayList<>();
    }

    final FirewallHandler mHandler;

    private final class FirewallHandler extends Handler {
        public FirewallHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            readRulesDir(getRulesDir());
        }
    };

    /**
     * Monitors for the creation/deletion/modification of any .xml files in the rule directory
     */
    private class RuleObserver extends FileObserver {
        private static final int MONITORED_EVENTS = FileObserver.CREATE|FileObserver.MOVED_TO|
                FileObserver.CLOSE_WRITE|FileObserver.DELETE|FileObserver.MOVED_FROM;

        public RuleObserver(File monitoredDir) {
            super(monitoredDir.getAbsolutePath(), MONITORED_EVENTS);
        }

        @Override
        public void onEvent(int event, String path) {
            if (path != null && path.endsWith(".xml")) {
                // we wait 250ms before taking any action on an event, in order to dedup multiple
                // events. E.g. a delete event followed by a create event followed by a subsequent
                // write+close event
                mHandler.removeMessages(0);
                mHandler.sendEmptyMessageDelayed(0, 250);
            }
        }
    }

    /**
     * This interface contains the methods we need from ActivityManagerService. This allows AMS to
     * export these methods to us without making them public, and also makes it easier to test this
     * component.
     */
    public interface AMSInterface {
        int checkComponentPermission(String permission, int pid, int uid,
                int owningUid, boolean exported);
        Object getAMSLock();
        ContentResolver getContentResolver();
    }

    /**
     * Checks if the caller has access to a component
     *
     * @param permission If present, the caller must have this permission
     * @param pid The pid of the caller
     * @param uid The uid of the caller
     * @param owningUid The uid of the application that owns the component
     * @param exported Whether the component is exported
     * @return True if the caller can access the described component
     */
    boolean checkComponentPermission(String permission, int pid, int uid, int owningUid,
            boolean exported) {
        return mAms.checkComponentPermission(permission, pid, uid, owningUid, exported) ==
                PackageManager.PERMISSION_GRANTED;
    }

    boolean signaturesMatch(int uid1, int uid2) {
        final long token = Binder.clearCallingIdentity();
        try {
            // Compare signatures of two packages for different users.
            return getPackageManager()
                    .checkUidSignaturesForAllUsers(uid1, uid2) == PackageManager.SIGNATURE_MATCH;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

}
