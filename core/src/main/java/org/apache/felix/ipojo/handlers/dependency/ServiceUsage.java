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

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Object managing thread local copy of required services.
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class ServiceUsage extends ThreadLocal<ServiceUsage.Usage> {
    
    /**
     * Structure contained in the Thread Local.
     */
    public static class Usage {

        /**
         * Stack Size.
         */
        int m_stack = 0;
        /**
         * Object to inject.
         */
        private Object m_object;

        /**
         * Tracks the number of component method called
         * in the current thread.
         */
        int m_componentStack = 0;

        private volatile boolean m_uptodate = false;

        /**
         * Increment the stack level from the first
         * service get.
         */
        public void inc() {
            m_stack++;
        }

        /**
         * Increment the component stack level.
         */
        public void incComponentStack() {
            m_componentStack++;
        }

        /**
         * Decrement the stack level.
         * @return  true if the stack is 0 after the decrement.
         */
        public boolean dec() {
            m_stack--;
            return m_stack == 0;
        }

        /**
         * Decrement the component stack level.
         * @return  true if the stack is 0 after the decrement.
         */
        public boolean decComponentStack() {
            m_componentStack--;
            return m_componentStack == 0;
        }

        public Object getObject()
        {
            return m_object;
        }

        public void setObject(Object object)
        {
            m_object = object;
            m_uptodate = true;
        }

        public boolean isUpToDate()
        {
            return m_uptodate;
        }
    }

    /**
     * Keep track of provided {@link Usage}s to be able to invalid them (see {@link #markOutdated()})
     * Weak reference based to avoid preventing the Usages from being GC'd
     */
    private Set<Usage> usages = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Usage, Boolean>()));

    /**
     * Marks all associated {@link Usage} as not being up to date, meaning
     * they should be re-computed
     */
    public void markOutdated() {
        synchronized (usages)
        {
            for (Usage usage : usages)
            {
                usage.m_uptodate = false;
            }
        }
    }
    
    /**
     * Initialize the cached object.
     * @return an empty Usage object.
     * @see java.lang.ThreadLocal#initialValue()
     */
    public Usage initialValue() {
        Usage usage = new Usage();
        usages.add(usage);
        return usage;
    }
}
