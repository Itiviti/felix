/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.ipojo.handlers.dependency;

import static org.fest.assertions.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.felix.ipojo.InstanceManager;
import org.apache.felix.ipojo.test.MockBundle;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

public class ServiceUsageTest
{
    static class ThreadPerTaskExecutor extends AbstractExecutorService
    {
        private final Object mutex = new Object();
        private List<Thread> pool = new ArrayList<>();

        @Override
        public void execute(Runnable command)
        {
            synchronized (mutex)
            {
                if (isShutdown())
                {
                    throw new RejectedExecutionException("Cannot accept new task because thread pool has been shutdown");
                }
                Thread thread = new Thread(command);
                pool.add(thread);
                thread.start();
            }
        }

        @Override
        public List<Runnable> shutdownNow()
        {
            synchronized (mutex)
            {
                if (!isShutdown())
                {
                    pool.forEach(thread -> thread.interrupt());
                    pool = null;
                }
            }
            return Collections.emptyList();
        }

        @Override
        public void shutdown() { shutdownNow(); }

        @Override
        public boolean isShutdown()
        {
            synchronized (mutex)
            {
                return pool == null;
            }
        }

        @Override
        public boolean isTerminated() { return isShutdown(); }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    ExecutorService threads;
    Dependency dependency;
    ServiceUsage serviceUsage;
    Set<ServiceUsage.Usage> allUsages;

    @Before
    public void setup()
    {
        threads = new ThreadPerTaskExecutor();
        Bundle bundle = new MockBundle(Dependency.class.getClassLoader());
        BundleContext context = Mockito.mock(BundleContext.class);
        Mockito.when(context.getBundle()).thenReturn(bundle);
        DependencyHandler handler = Mockito.mock(DependencyHandler.class);
        dependency = new Dependency(handler, "a_field", Object.class, null, false, false, false,
            false, "dep", context, Dependency.DYNAMIC_BINDING_POLICY, null, null, null);
        dependency.start();
        InstanceManager instanceManager = Mockito.mock(InstanceManager.class);
        Mockito.when(handler.getInstanceManager()).thenReturn(instanceManager);
        Mockito.when(instanceManager.getState()).thenReturn(InstanceManager.STOPPED);
        serviceUsage = getPrivateField(dependency, "m_usage");
        allUsages = getPrivateField(serviceUsage, "usages");
    }

    @After
    public void tearDown()
    {
        threads.shutdown();
    }

    @Test
    public void testUsageCachedByThread()
    {
        dependency.onEntry(null, null, null);
        dependency.onGet(null, null, null);
        ServiceUsage.Usage usage = serviceUsage.get();
        dependency.onExit(null, null, null);
        dependency.onFinally(null, null);
        assertThat(serviceUsage.get()).isSameAs(usage);
        dependency.onServiceModification(null);
        assertThat(serviceUsage.get()).isSameAs(usage);
    }

    @Test
    public void testUsageUpToDate() throws InterruptedException
    {
        assertThat(serviceUsage.get().isUpToDate()).isFalse();
        allUsages.forEach(usages -> assertThat(usages.isUpToDate()).isFalse());
        dependency.onGet(null, null, null);
        assertThat(serviceUsage.get().isUpToDate()).isTrue();
        Collection<Callable<Object>> runnables = new ArrayList<>();
        for (int i = 0; i < 10; i++)
            runnables.add(() -> dependency.onGet(null, null, null));
        threads.invokeAll(runnables, 5, TimeUnit.SECONDS);
        synchronized (allUsages)
        {
            assertThat(allUsages.stream().filter(Objects::nonNull).map(ServiceUsage.Usage::isUpToDate).collect(Collectors.toList())).hasSize(11).containsOnly(true);
        }
        dependency.onServiceModification(null);
        assertThat(serviceUsage.get().isUpToDate()).isFalse();
        synchronized (allUsages)
        {
            assertThat(allUsages.stream().filter(Objects::nonNull).map(ServiceUsage.Usage::isUpToDate).collect(Collectors.toList())).hasSize(11).containsOnly(false);
        }
        dependency.onExit(null, null, null);
        dependency.onFinally(null, null);
        dependency.onGet(null, null, null);
        assertThat(serviceUsage.get().isUpToDate()).isTrue();
    }

    @Test
    public void testWeakReference() throws InterruptedException
    {
        Collection<Callable<Object>> runnables = new ArrayList<>();
        for (int i = 0; i < 10; i++)
            runnables.add(() -> {
                dependency.onGet(null, null, null);
                Thread.sleep(1000);
                return null;
            });
        threads.invokeAll(runnables, 5, TimeUnit.SECONDS);
        synchronized (allUsages)
        {
            assertThat(allUsages.stream().filter(Objects::nonNull).collect(Collectors.toList())).hasSize(10);
        }
        threads.shutdownNow();
        Thread.sleep(500);
        System.gc();
        dependency.onGet(null, null, null);
        synchronized (allUsages)
        {
            List<ServiceUsage.Usage> usages = allUsages.stream().filter(Objects::nonNull).collect(Collectors.toList());
            assertThat(usages).hasSize(1);
            assertThat(usages.get(0)).isSameAs(serviceUsage.get());
        }
    }

    private static <T, C> T getPrivateField(C object, String fieldName)
    {
        try
        {
            Field field = object.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(object);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testConcurrency() throws InterruptedException
    {
        int nbThreads = 5000;
        final CountDownLatch barrier = new CountDownLatch(1);
        final CountDownLatch barrierAssertion = new CountDownLatch(nbThreads);
        final CountDownLatch barrierExit = new CountDownLatch(1);
        ConcurrentLinkedQueue<Exception> exceptions = new ConcurrentLinkedQueue<Exception>();
        for (int i = 0; i < nbThreads; i++)
            threads.submit(() -> {
                try
                {
                    barrier.await();
                    dependency.onGet(null, null, null);
                }
                catch (Exception e)
                {
                    exceptions.add(e);
                }
                barrierAssertion.countDown();
                try
                {
                    barrierExit.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            });
        try
        {
            Thread.sleep(500);
            barrier.countDown();
            barrierAssertion.await(10, TimeUnit.SECONDS);
            assertThat(exceptions).isEmpty();
            assertThat(allUsages.size()).isEqualTo(nbThreads);
        }
        finally
        {
            barrierExit.countDown();
            threads.shutdown();
        }
    }
}
