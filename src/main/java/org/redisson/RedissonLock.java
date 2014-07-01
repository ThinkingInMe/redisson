/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.io.Serializable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import org.redisson.connection.ConnectionManager;
import org.redisson.connection.PubSubConnectionEntry;
import org.redisson.core.RLock;

import com.lambdaworks.redis.RedisConnection;
import com.lambdaworks.redis.pubsub.RedisPubSubAdapter;

/**
 * Distributed implementation of {@link java.util.concurrent.locks.Lock}
 * Implements reentrant lock.
 *
 * @author Nikita Koksharov
 *
 */
public class RedissonLock extends RedissonObject implements RLock {

    public static class LockValue implements Serializable {

        private static final long serialVersionUID = -8895632286065689476L;

        private UUID id;
        private Long threadId;
        // need for reentrant support
        private int counter;

        public LockValue() {
        }

        public LockValue(UUID id, Long threadId) {
            super();
            this.id = id;
            this.threadId = threadId;
        }

        public void decCounter() {
            counter--;
        }

        public void incCounter() {
            counter++;
        }

        public int getCounter() {
            return counter;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((id == null) ? 0 : id.hashCode());
            result = prime * result + ((threadId == null) ? 0 : threadId.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            LockValue other = (LockValue) obj;
            if (id == null) {
                if (other.id != null)
                    return false;
            } else if (!id.equals(other.id))
                return false;
            if (threadId == null) {
                if (other.threadId != null)
                    return false;
            } else if (!threadId.equals(other.threadId))
                return false;
            return true;
        }

    }

    private final UUID id;

    private static final Integer unlockMessage = 0;

    private static final ConcurrentMap<String, RedissonLockEntry> ENTRIES = new ConcurrentHashMap<String, RedissonLockEntry>();

    private PubSubConnectionEntry pubSubEntry;

    RedissonLock(ConnectionManager connectionManager, String name, UUID id) {
        super(connectionManager, name);
        this.id = id;
    }

    private void release() {
        while (true) {
            RedissonLockEntry entry = ENTRIES.get(getName());
            if (entry == null) {
                return;
            }
            RedissonLockEntry newEntry = new RedissonLockEntry(entry);
            newEntry.release();
            if (ENTRIES.replace(getName(), entry, newEntry)) {
                return;
            }
        }
    }
    
    private Promise<Boolean> aquire() {
        while (true) {
            RedissonLockEntry entry = ENTRIES.get(getName());
            if (entry != null) {
                RedissonLockEntry newEntry = new RedissonLockEntry(entry);
                newEntry.aquire();
                if (ENTRIES.replace(getName(), entry, newEntry)) {
                    return newEntry.getPromise();
                }
            } else {
                return null;
            }
        }
    }
    
    private Future<Boolean> subscribe() {
        Promise<Boolean> promise = aquire();
        if (promise != null) {
            return promise;
        }

        Promise<Boolean> newPromise = newPromise();
        final RedissonLockEntry value = new RedissonLockEntry(newPromise);
        value.aquire();
        RedissonLockEntry oldValue = ENTRIES.putIfAbsent(getName(), value);
        if (oldValue != null) {
            Promise<Boolean> oldPromise = aquire();
            if (oldPromise == null) {
                return subscribe();
            }
            return oldPromise;
        }

        value.getLatch().acquireUninterruptibly();

        RedisPubSubAdapter<String, Integer> listener = new RedisPubSubAdapter<String, Integer>() {

            @Override
            public void subscribed(String channel, long count) {
                if (getChannelName().equals(channel)) {
                    value.getPromise().setSuccess(true);
                }
            }

            @Override
            public void message(String channel, Integer message) {
                if (message.equals(unlockMessage) && getChannelName().equals(channel)) {
                    value.getLatch().release();
                }
            }

        };

        pubSubEntry = connectionManager.subscribe(listener, getChannelName());
        return newPromise;
    }

    @Override
    public void lock() {
        try {
            lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
    }

    private String getKeyName() {
        return "redisson__lock__" + getName();
    }

    private String getChannelName() {
        return "redisson__lock__channel__" + getName();
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        while (!tryLock()) {
            // waiting for message
            RedissonLockEntry entry = ENTRIES.get(getName());
            if (entry != null) {
                entry.getLatch().acquire();
            }
        }
    }

    @Override
    public boolean tryLock() {
        Future<Boolean> promise = subscribe();
        try {
            promise.awaitUninterruptibly();
            
            return tryLockInner();
        } finally {
            close();
        }
    }

    private boolean tryLockInner() {
        LockValue currentLock = new LockValue(id, Thread.currentThread().getId());
        currentLock.incCounter();
        
        RedisConnection<Object, Object> connection = connectionManager.connectionWriteOp();
        try {
            Boolean res = connection.setnx(getKeyName(), currentLock);
            if (!res) {
                LockValue lock = (LockValue) connection.get(getKeyName());
                if (lock != null && lock.equals(currentLock)) {
                    lock.incCounter();
                    connection.set(getKeyName(), lock);
                    return true;
                }
            }
            return res;
        } finally {
            connectionManager.releaseWrite(connection);
        }
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        Future<Boolean> promise = subscribe();
        try {
            if (!promise.awaitUninterruptibly(time, unit)) {
                return false;
            }
            
            time = unit.toMillis(time);
            while (!tryLockInner()) {
                if (time <= 0) {
                    return false;
                }
                long current = System.currentTimeMillis();
                // waiting for message
                RedissonLockEntry entry = ENTRIES.get(getName());
                if (entry != null) {
                    entry.getLatch().tryAcquire(time, TimeUnit.MILLISECONDS);
                }
                long elapsed = System.currentTimeMillis() - current;
                time -= elapsed;
            }
            return true;
        } finally {
            close();
        }
    }

    @Override
    public void unlock() {
        Future<Boolean> promise = subscribe();
        try {
            promise.awaitUninterruptibly();
            
            RedisConnection<Object, Object> connection = connectionManager.connectionWriteOp();
            try {
                LockValue lock = (LockValue) connection.get(getKeyName());
                LockValue currentLock = new LockValue(id, Thread.currentThread().getId());
                if (lock != null && lock.equals(currentLock)) {
                    if (lock.getCounter() > 1) {
                        lock.decCounter();
                        connection.set(getKeyName(), lock);
                    } else {
                        unlock(connection);
                    }
                } else {
                    // could be deleted
//                    throw new IllegalMonitorStateException("Attempt to unlock lock, not locked by current id: "
//                            + id + " thread-id: " + Thread.currentThread().getId());
                }
            } finally {
                connectionManager.releaseWrite(connection);
            }
        } finally {
            close();
        }
    }

    private void unlock(RedisConnection<Object, Object> connection) {
        int counter = 0;
        while (counter < 5) {
            connection.multi();
            connection.del(getKeyName());
            connection.publish(getChannelName(), unlockMessage);
            if (connection.exec().size() == 2) {
                return;
            }
            counter++;
        }
        throw new IllegalStateException("Can't unlock lock after 5 attempts. Current id: "
                + id + " thread-id: " + Thread.currentThread().getId());
    }

    @Override
    public Condition newCondition() {
        // TODO implement
        throw new UnsupportedOperationException();
    }

    @Override
    public void forceUnlock() {
        Future<Boolean> promise = subscribe();
        try {
            promise.awaitUninterruptibly();
            
            RedisConnection<Object, Object> connection = connectionManager.connectionWriteOp();
            try {
                LockValue lock = (LockValue) connection.get(getKeyName());
                if (lock != null) {
                    unlock(connection);
                }
            } finally {
                connectionManager.releaseWrite(connection);
            }
        } finally {
            close();
        }
    }

    @Override
    public boolean isLocked() {
        Future<Boolean> promise = subscribe();
        try {
            promise.awaitUninterruptibly();
            
            RedisConnection<Object, Object> connection = connectionManager.connectionReadOp();
            try {
                LockValue lock = (LockValue) connection.get(getKeyName());
                return lock != null;
            } finally {
                connectionManager.releaseRead(connection);
            }
        } finally {
            close();
        }
    }

    @Override
    public boolean isHeldByCurrentThread() {
        Future<Boolean> promise = subscribe();
        try {
            promise.awaitUninterruptibly();
            
            RedisConnection<Object, Object> connection = connectionManager.connectionReadOp();
            try {
                LockValue lock = (LockValue) connection.get(getKeyName());
                LockValue currentLock = new LockValue(id, Thread.currentThread().getId());
                return lock != null && lock.equals(currentLock);
            } finally {
                connectionManager.releaseRead(connection);
            }
        } finally {
            close();
        }
    }

    @Override
    public int getHoldCount() {
        Future<Boolean> promise = subscribe();
        try {
            promise.awaitUninterruptibly();
            
            RedisConnection<Object, Object> connection = connectionManager.connectionReadOp();
            try {
                LockValue lock = (LockValue) connection.get(getKeyName());
                LockValue currentLock = new LockValue(id, Thread.currentThread().getId());
                if (lock != null && lock.equals(currentLock)) {
                    return lock.getCounter();
                }
                return 0;
            } finally {
                connectionManager.releaseRead(connection);
            }
        } finally {
            close();
        }
    }

    @Override
    public void delete() {
        forceUnlock();
        ENTRIES.remove(getName());
    }

    public void close() {
        release();
        
        connectionManager.getGroup().schedule(new Runnable() {
            @Override
            public void run() {
                RedissonLockEntry entry = ENTRIES.get(getName());
                if (entry != null 
                        && entry.isFree() 
                            && ENTRIES.remove(getName(), entry)) {
                    connectionManager.unsubscribe(pubSubEntry, getChannelName());
                }
            }
        }, 15, TimeUnit.SECONDS);
    }
    
}
