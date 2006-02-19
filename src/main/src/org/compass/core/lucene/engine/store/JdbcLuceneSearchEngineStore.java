/*
 * Copyright 2004-2006 the original author or authors.
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

package org.compass.core.lucene.engine.store;

import java.io.IOException;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.sql.DataSource;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.jdbc.JdbcDirectory;
import org.apache.lucene.store.jdbc.JdbcDirectorySettings;
import org.apache.lucene.store.jdbc.JdbcFileEntrySettings;
import org.apache.lucene.store.jdbc.JdbcStoreException;
import org.apache.lucene.store.jdbc.datasource.DataSourceUtils;
import org.apache.lucene.store.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.apache.lucene.store.jdbc.dialect.Dialect;
import org.apache.lucene.store.jdbc.dialect.DialectResolver;
import org.apache.lucene.store.jdbc.index.FetchPerTransactionJdbcIndexInput;
import org.apache.lucene.store.jdbc.support.JdbcTable;
import org.compass.core.CompassException;
import org.compass.core.config.CompassSettings;
import org.compass.core.config.ConfigurationException;
import org.compass.core.engine.SearchEngine;
import org.compass.core.engine.SearchEngineException;
import org.compass.core.engine.event.SearchEngineEventManager;
import org.compass.core.engine.event.SearchEngineLifecycleEventListener;
import org.compass.core.lucene.LuceneEnvironment;
import org.compass.core.lucene.engine.LuceneSearchEngineFactory;
import org.compass.core.lucene.engine.store.jdbc.DataSourceProvider;
import org.compass.core.lucene.engine.store.jdbc.DriverManagerDataSourceProvider;
import org.compass.core.mapping.CompassMapping;
import org.compass.core.util.ClassUtils;

/**
 * @author kimchy
 */
public class JdbcLuceneSearchEngineStore extends AbstractLuceneSearchEngineStore {

    private String url;

    private String subContext;

    private JdbcDirectorySettings jdbcSettings;

    private DataSource dataSource;

    private DataSourceProvider dataSourceProvider;

    private Dialect dialect;

    private boolean managed;

    private Map cachedJdbcTables = new HashMap();

    public JdbcLuceneSearchEngineStore(String url, String subContext) {
        this.subContext = subContext;
        this.url = url;
    }

    public void configure(LuceneSearchEngineFactory searchEngineFactory, CompassSettings settings, CompassMapping mapping) throws CompassException {
        String dataSourceProviderClassName = settings.getSetting(LuceneEnvironment.JdbcStore.DataSourceProvider.CLASS,
                DriverManagerDataSourceProvider.class.getName());
        try {
            dataSourceProvider =
                    (DataSourceProvider) ClassUtils.forName(dataSourceProviderClassName).newInstance();
            dataSourceProvider.configure(url, settings);
            this.dataSource = dataSourceProvider.getDataSource();
        } catch (Exception e) {
            throw new CompassException("Failed to configure data source provider [" + dataSourceProviderClassName + "]", e);
        }

        String dialectClassName = settings.getSetting(LuceneEnvironment.JdbcStore.DIALECT, null);
        if (dialectClassName == null) {
            try {
                dialect = new DialectResolver().getDialect(dataSource);
            } catch (JdbcStoreException e) {
                throw new ConfigurationException("Failed to auto detect dialect", e);
            }
        } else {
            try {
                dialect = (Dialect) ClassUtils.forName(dialectClassName).newInstance();
            } catch (Exception e) {
                throw new ConfigurationException("Failed to configure dialect [" + dialectClassName + "]");
            }
        }


        managed = settings.getSettingAsBoolean(LuceneEnvironment.JdbcStore.MANAGED, false);
        if (!managed) {
            this.dataSource = new TransactionAwareDataSourceProxy(this.dataSource);
        }

        jdbcSettings = new JdbcDirectorySettings();
        jdbcSettings.setNameColumnName(settings.getSetting(LuceneEnvironment.JdbcStore.DDL.NAME_NAME, jdbcSettings.getNameColumnName()));
        jdbcSettings.setValueColumnName(settings.getSetting(LuceneEnvironment.JdbcStore.DDL.VALUE_NAME, jdbcSettings.getValueColumnName()));
        jdbcSettings.setSizeColumnName(settings.getSetting(LuceneEnvironment.JdbcStore.DDL.SIZE_NAME, jdbcSettings.getSizeColumnName()));
        jdbcSettings.setLastModifiedColumnName(settings.getSetting(LuceneEnvironment.JdbcStore.DDL.LAST_MODIFIED_NAME, jdbcSettings.getLastModifiedColumnName()));
        jdbcSettings.setDeletedColumnName(settings.getSetting(LuceneEnvironment.JdbcStore.DDL.DELETED_NAME, jdbcSettings.getDeletedColumnName()));

        jdbcSettings.setNameColumnLength(settings.getSettingAsInt(LuceneEnvironment.JdbcStore.DDL.NAME_LENGTH, jdbcSettings.getNameColumnLength()));
        jdbcSettings.setValueColumnLengthInK(settings.getSettingAsInt(LuceneEnvironment.JdbcStore.DDL.VALUE_LENGTH, jdbcSettings.getValueColumnLengthInK()));

        jdbcSettings.setUseCommitLocks(settings.getSettingAsBoolean(LuceneEnvironment.JdbcStore.USE_COMMIT_LOCKS, jdbcSettings.isUseCommitLocks()));
        jdbcSettings.setDeleteMarkDeletedDelta(settings.getSettingAsLong(LuceneEnvironment.JdbcStore.DELETE_MARK_DELETED_DELTA, jdbcSettings.getDeleteMarkDeletedDelta()));
        jdbcSettings.setQueryTimeout(settings.getSettingAsInt(LuceneEnvironment.Transaction.LOCK_TIMEOUT, jdbcSettings.getQueryTimeout()));

        try {
            jdbcSettings.setLockClass(settings.getSettingAsClass(LuceneEnvironment.JdbcStore.LOCK_TYPE, jdbcSettings.getLockClass()));
        } catch (ClassNotFoundException e) {
            throw new CompassException("Failed to create jdbc lock class [" + settings.getSetting(LuceneEnvironment.JdbcStore.LOCK_TYPE) + "]");
        }

        if (dialect.supportTransactionalScopedBlobs() &&
                !settings.getSettingAsBoolean(LuceneEnvironment.JdbcStore.Connection.AUTO_COMMIT, false)) {
              // Use FetchPerTransaction is dialect supports it
            jdbcSettings.getDefaultFileEntrySettings().setClassSetting(JdbcFileEntrySettings.INDEX_INPUT_TYPE_SETTING,
                    FetchPerTransactionJdbcIndexInput.class);
        }

        Map fileEntries = settings.getSettingGroups(LuceneEnvironment.JdbcStore.FileEntry.PREFIX);
        for (Iterator it = fileEntries.keySet().iterator(); it.hasNext();) {
            String fileEntryName = (String) it.next();
            if (log.isInfoEnabled()) {
                log.info("Configuring file entry [" + fileEntryName + "]");
            }
            CompassSettings compassFeSettings = (CompassSettings) fileEntries.get(fileEntryName);
            JdbcFileEntrySettings jdbcFileEntrySettings = jdbcSettings.getFileEntrySettingsWithoutDefault(fileEntryName);
            if (jdbcFileEntrySettings == null) {
                jdbcFileEntrySettings = new JdbcFileEntrySettings();
            }
            // iterate over all the settings and copy them to the jdbc settings
            for (Iterator feIt = compassFeSettings.keySet().iterator(); feIt.hasNext();) {
                String feSetting = (String) feIt.next();
                jdbcFileEntrySettings.setSetting(feSetting, compassFeSettings.getSetting(feSetting));
            }
            jdbcSettings.registerFileEntrySettings(fileEntryName, jdbcFileEntrySettings);
        }

        super.configure(searchEngineFactory, settings, mapping);
    }

    protected void doClose() {
        this.dataSourceProvider.closeDataSource();
    }

    public void performScheduledTasks() throws SearchEngineException {
        String[] subIndexes = getSubIndexes();
        Exception last = null;
        for (int i = 0; i < subIndexes.length; i++) {
            try {
                JdbcDirectory jdbcDirectory = (JdbcDirectory) getDirectoryBySubIndex(subIndexes[i], false);
                jdbcDirectory.deleteMarkDeleted();
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) {
            throw new SearchEngineException("Failed to clear mark deleted", last);
        }
    }

    protected Directory doGetDirectoryForPath(String path, boolean create) throws SearchEngineException {
        String totalPath = subContext + "_" + path;
        JdbcTable jdbcTable = (JdbcTable) cachedJdbcTables.get(totalPath);
        if (jdbcTable == null) {
            jdbcTable = new JdbcTable(jdbcSettings, dialect, totalPath);
            cachedJdbcTables.put(totalPath, jdbcTable);
        }
        JdbcDirectory dir = new JdbcDirectory(dataSource, jdbcTable);
        if (create) {
            try {
                dir.create();
            } catch (IOException e) {
                throw new SearchEngineException("Failed to create dir [" + path + "]", e);
            }
        }
        return dir;
    }

    public void createIndex() throws SearchEngineException {
        try {
            deleteIndex();
        } catch (Exception e) {
            // do nothing
        }
        super.createIndex();
    }

    public void deleteIndex() throws SearchEngineException {
        String[] subIndexes = getSubIndexes();
        for (int i = 0; i < subIndexes.length; i++) {
            template.executeForSubIndex(subIndexes[i], false,
                    new LuceneStoreCallback() {
                        public Object doWithStore(Directory dir) throws IOException {
                            ((JdbcDirectory) dir).delete();
                            return null;
                        }
                    });

        }
    }

    protected Boolean indexExists(Directory dir) throws IOException {
        try {
            // for databases that fail if there is no table (like postgres)
            if (dialect.supportsTableExists()) {
                boolean tableExists = ((JdbcDirectory) dir).tableExists();
                if (!tableExists) {
                    return Boolean.FALSE;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (UnsupportedOperationException e) {
            e.printStackTrace();
        }
        return super.indexExists(dir);
    }

    public void registerEventListeners(SearchEngine searchEngine, SearchEngineEventManager eventManager) {
        if (managed) {
            eventManager.registerLifecycleListener(new ManagedEventListeners());
        } else {
            eventManager.registerLifecycleListener(new NoneManagedEventListeners());
        }
    }

    protected CopyFromHolder doBeforeCopyFrom() throws SearchEngineException {
        for (int i = 0; i < getSubIndexes().length; i++) {
            final String subIndex = getSubIndexes()[i];
            template.executeForSubIndex(subIndex, false, new LuceneStoreCallback() {
                public Object doWithStore(final Directory dest) {
                    JdbcDirectory jdbcDirectory = (JdbcDirectory) dest;
                    try {
                        jdbcDirectory.deleteContent();
                    } catch (IOException e) {
                        throw new SearchEngineException("Failed to delete content of [" + subIndex + "]", e);
                    }
                    return null;
                }
            });
        }
        CopyFromHolder holder = new CopyFromHolder();
        holder.createOriginalDirectory = false;
        return holder;
    }

    private class ManagedEventListeners implements SearchEngineLifecycleEventListener {

        public void beforeBeginTransaction() throws SearchEngineException {

        }

        public void afterBeginTransaction() throws SearchEngineException {

        }

        public void afterPrepare() throws SearchEngineException {

        }

        public void afterCommit(boolean onePhase) throws SearchEngineException {
            Connection conn;
            try {
                conn = DataSourceUtils.getConnection(dataSource);
            } catch (JdbcStoreException e) {
                throw new SearchEngineException("Failed to get connection", e);
            }
            FetchPerTransactionJdbcIndexInput.releaseBlobs(conn);
            DataSourceUtils.releaseConnection(conn);
        }

        public void afterRollback() throws SearchEngineException {
            Connection conn;
            try {
                conn = DataSourceUtils.getConnection(dataSource);
            } catch (JdbcStoreException e) {
                throw new SearchEngineException("Failed to get connection", e);
            }
            FetchPerTransactionJdbcIndexInput.releaseBlobs(conn);
            DataSourceUtils.releaseConnection(conn);
        }

        public void close() throws SearchEngineException {

        }
    }

    private class NoneManagedEventListeners implements SearchEngineLifecycleEventListener {

        private Connection connection;

        public void beforeBeginTransaction() throws SearchEngineException {
            try {
                connection = DataSourceUtils.getConnection(dataSource);
            } catch (JdbcStoreException e) {
                throw new SearchEngineException("Failed to open db connection", e);
            }
        }

        public void afterBeginTransaction() throws SearchEngineException {
        }

        public void afterPrepare() throws SearchEngineException {
        }

        public void afterCommit(boolean onePhase) throws SearchEngineException {
            try {
                DataSourceUtils.commitConnectionIfPossible(connection);
            } catch (JdbcStoreException e) {
                throw new SearchEngineException("Failed to commit database transcation", e);
            } finally {
                DataSourceUtils.releaseConnection(connection);
                this.connection = null;
            }
        }

        public void afterRollback() throws SearchEngineException {
            try {
                DataSourceUtils.rollbackConnectionIfPossible(connection);
            } catch (JdbcStoreException e) {
                throw new SearchEngineException("Failed to rollback database transcation", e);
            } finally {
                DataSourceUtils.releaseConnection(connection);
                this.connection = null;
            }
        }

        public void close() throws SearchEngineException {
        }
    }
}
