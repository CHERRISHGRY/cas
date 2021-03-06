package org.apereo.cas.ticket.registry.support;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import org.apereo.cas.config.JpaTicketRegistryConfiguration;
import org.apereo.cas.configuration.model.support.jpa.ticketregistry.JpaTicketRegistryProperties;
import org.apereo.cas.configuration.support.Beans;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Unit test for {@link JpaLockingStrategy}.
 *
 * @author Marvin S. Addison
 * @since 3.0.0
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {RefreshAutoConfiguration.class, JpaTicketRegistryConfiguration.class})
public class JpaLockingStrategyTests {
    /**
     * Number of clients contending for lock in concurrent test.
     */
    private static final int CONCURRENT_SIZE = 13;
    
    private final transient Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    @Qualifier("ticketTransactionManager")
    private PlatformTransactionManager txManager;

    @Autowired
    @Qualifier("ticketEntityManagerFactory")
    private EntityManagerFactory factory;

    @Autowired
    @Qualifier("dataSourceTicket")
    private DataSource dataSource;

    @Before
    public void setUp() {

    }

    /**
     * Test basic acquire/release semantics.
     *
     * @throws Exception On errors.
     */
    @Test
    public void verifyAcquireAndRelease() throws Exception {
        final String appId = "basic";
        final String uniqueId = appId + "-1";
        final LockingStrategy lock = newLockTxProxy(appId, uniqueId, JpaTicketRegistryProperties.DEFAULT_LOCK_TIMEOUT);
        try {
            assertTrue(lock.acquire());
            assertEquals(uniqueId, getOwner(appId));
            lock.release();
            assertNull(getOwner(appId));
        } catch (final Exception e) {
            logger.debug("testAcquireAndRelease produced an error", e);
            fail("testAcquireAndRelease failed");
        }
    }

    /**
     * Test lock expiration.
     *
     * @throws Exception On errors.
     */
    @Test
    public void verifyLockExpiration() throws Exception {
        final String appId = "expquick";
        final String uniqueId = appId + "-1";
        final LockingStrategy lock = newLockTxProxy(appId, uniqueId, "1");
        try {
            assertTrue(lock.acquire());
            assertEquals(uniqueId, getOwner(appId));
            assertFalse(lock.acquire());
            Thread.sleep(1500);
            assertTrue(lock.acquire());
            assertEquals(uniqueId, getOwner(appId));
            lock.release();
            assertNull(getOwner(appId));
        } catch (final Exception e) {
            logger.debug("testLockExpiration produced an error", e);
            fail("testLockExpiration failed");
        }
    }

    /**
     * Verify non-reentrant behavior.
     */
    @Test
    public void verifyNonReentrantBehavior() {
        final String appId = "reentrant";
        final String uniqueId = appId + "-1";
        final LockingStrategy lock = newLockTxProxy(appId, uniqueId, JpaTicketRegistryProperties.DEFAULT_LOCK_TIMEOUT);
        try {
            assertTrue(lock.acquire());
            assertEquals(uniqueId, getOwner(appId));
            assertFalse(lock.acquire());
            lock.release();
            assertNull(getOwner(appId));
        } catch (final Exception e) {
            logger.debug("testNonReentrantBehavior produced an error", e);
            fail("testNonReentrantBehavior failed.");
        }
    }

    /**
     * Test concurrent acquire/release semantics.
     */
    @Test
    public void verifyConcurrentAcquireAndRelease() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_SIZE);
        try {
            testConcurrency(executor, Lists.newArrayList(getConcurrentLocks("concurrent-new")));
        } catch (final Exception e) {
            logger.debug("testConcurrentAcquireAndRelease produced an error", e);
            fail("testConcurrentAcquireAndRelease failed.");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Test concurrent acquire/release semantics for existing lock.
     */
    @Test
    public void verifyConcurrentAcquireAndReleaseOnExistingLock() throws Exception {
        final LockingStrategy[] locks = getConcurrentLocks("concurrent-exists");
        locks[0].acquire();
        locks[0].release();
        final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_SIZE);
        try {
            testConcurrency(executor, Lists.newArrayList(locks));
        } catch (final Exception e) {
            logger.debug("testConcurrentAcquireAndReleaseOnExistingLock produced an error", e);
            fail("testConcurrentAcquireAndReleaseOnExistingLock failed.");
        } finally {
            executor.shutdownNow();
        }
    }

    private LockingStrategy[] getConcurrentLocks(final String appId) {
        final LockingStrategy[] locks = new LockingStrategy[CONCURRENT_SIZE];
        for (int i = 1; i <= locks.length; i++) {
            locks[i - 1] = newLockTxProxy(appId, appId + '-' + i, JpaTicketRegistryProperties.DEFAULT_LOCK_TIMEOUT);
        }
        return locks;
    }

    private LockingStrategy newLockTxProxy(final String appId, final String uniqueId, final String ttl) {
        final JpaLockingStrategy lock = new JpaLockingStrategy();
        lock.entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
        lock.setApplicationId(appId);
        lock.setUniqueId(uniqueId);
        lock.setLockTimeout(Beans.newDuration(ttl).getSeconds());
        return (LockingStrategy) Proxy.newProxyInstance(
                JpaLockingStrategy.class.getClassLoader(),
                new Class[]{LockingStrategy.class},
                new TransactionalLockInvocationHandler(lock, this.txManager));
    }

    private String getOwner(final String appId) {
        final JdbcTemplate simpleJdbcTemplate = new JdbcTemplate(dataSource);
        final List<Map<String, Object>> results = simpleJdbcTemplate.queryForList(
                "SELECT unique_id FROM locks WHERE application_id=?", appId);
        if (results.isEmpty()) {
            return null;
        }
        return (String) results.get(0).get("unique_id");
    }

    private static void testConcurrency(final ExecutorService executor, 
                                        final Collection<LockingStrategy> locks) throws Exception {
        final List<Locker> lockers = new ArrayList<>(locks.size());
        lockers.addAll(locks.stream().map(Locker::new).collect(Collectors.toList()));

        final long lockCount = executor.invokeAll(lockers).stream().filter(result -> {
            try {
                return result.get();
            } catch (final InterruptedException | ExecutionException e) {
                throw Throwables.propagate(e);
            }
        }).count();
        assertTrue("Lock count should be <= 1 but was " + lockCount, lockCount <= 1);

        final List<Releaser> releasers = new ArrayList<>(locks.size());

        releasers.addAll(locks.stream().map(Releaser::new).collect(Collectors.toList()));
        final long releaseCount = executor.invokeAll(lockers).stream().filter(result -> {
            try {
                return result.get();
            } catch (final InterruptedException | ExecutionException e) {
                throw Throwables.propagate(e);
            }
        }).count();
        assertTrue("Release count should be <= 1 but was " + releaseCount, releaseCount <= 1);
    }

    private static class TransactionalLockInvocationHandler implements InvocationHandler {
        private final transient Logger logger = LoggerFactory.getLogger(this.getClass());
        private final JpaLockingStrategy jpaLock;
        private final PlatformTransactionManager txManager;

        TransactionalLockInvocationHandler(final JpaLockingStrategy lock,
                                           final PlatformTransactionManager txManager) {
            jpaLock = lock;
            this.txManager = txManager;
        }

        public JpaLockingStrategy getLock() {
            return this.jpaLock;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            return new TransactionTemplate(txManager).execute(status -> {
                try {
                    final Object result = method.invoke(jpaLock, args);
                    jpaLock.entityManager.flush();
                    logger.debug("Performed {} on {}", method.getName(), jpaLock);
                    return result;
                    // Force result of transaction to database
                } catch (final Exception e) {
                    throw new RuntimeException("Transactional method invocation failed.", e);
                }
            });
        }

    }

    private static class Locker implements Callable<Boolean> {
        private final transient Logger logger = LoggerFactory.getLogger(this.getClass());
        private final LockingStrategy lock;

        Locker(final LockingStrategy l) {
            lock = l;
        }

        @Override
        public Boolean call() throws Exception {
            try {
                return lock.acquire();
            } catch (final Exception e) {
                logger.debug("{} failed to acquire lock", lock, e);
                return false;
            }
        }
    }

    private static class Releaser implements Callable<Boolean> {
        private final transient Logger logger = LoggerFactory.getLogger(this.getClass());
        private final LockingStrategy lock;

        Releaser(final LockingStrategy l) {
            lock = l;
        }

        @Override
        public Boolean call() throws Exception {
            try {
                lock.release();
                return true;
            } catch (final Exception e) {
                logger.debug("{} failed to release lock", lock, e);
                return false;
            }
        }
    }

}
