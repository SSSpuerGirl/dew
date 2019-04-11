/*
 * Copyright 2019. the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ms.dew.core.cluster;

import com.hazelcast.core.Hazelcast;
import ms.dew.core.cluster.spi.hazelcast.HazelcastClusterLockWrap;
import ms.dew.core.cluster.spi.hazelcast.HazelcastClusterMQ;
import ms.dew.core.cluster.spi.hazelcast.HazelcastClusterMapWrap;
import ms.dew.core.cluster.test.ClusterLockTest;
import ms.dew.core.cluster.test.ClusterMQTest;
import ms.dew.core.cluster.test.ClusterMapTest;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * Cluster test.
 *
 * @author gudaoxuri
 */
@RunWith(SpringRunner.class)
@SpringBootApplication
@SpringBootTest
public class ClusterTest {

    @Autowired
    private HazelcastClusterMQ hazelcastClusterMQ;
    @Autowired
    private HazelcastClusterLockWrap hazelcastClusterLockWrap;
    @Autowired
    private HazelcastClusterMapWrap hazelcastClusterMapWrap;

    /**
     * Init.
     */
    @BeforeClass
    public static void init() {
        Hazelcast.newHazelcastInstance();
    }

    /**
     * Test mq.
     *
     * @throws InterruptedException the interrupted exception
     */
    @Test
    public void testMQ() throws InterruptedException {
        new ClusterMQTest().test(hazelcastClusterMQ);
    }

    /**
     * Test lock.
     *
     * @throws InterruptedException the interrupted exception
     */
    @Test
    public void testLock() throws InterruptedException {
        new ClusterLockTest().test(hazelcastClusterLockWrap.instance("test"));
    }

    /**
     * Test map.
     *
     * @throws InterruptedException the interrupted exception
     */
    @Test
    public void testMap() throws InterruptedException {
        new ClusterMapTest().test(hazelcastClusterMapWrap.instance("test", ClusterMapTest.TestMapObj.class));
    }

}
