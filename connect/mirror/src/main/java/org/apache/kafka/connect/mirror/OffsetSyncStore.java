/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.time.Duration;

/** Used internally by MirrorMaker. Stores offset syncs and performs offset translation. */
class OffsetSyncStore {
    private static final Logger log = LoggerFactory.getLogger(OffsetSyncStore.class);

    private KafkaConsumer<byte[], byte[]> consumer;
    private Map<TopicPartition, OffsetSync> offsetSyncs = new HashMap<>();
    private TopicPartition offsetSyncTopicPartition;

    OffsetSyncStore(MirrorConnectorConfig config) {
        consumer = new KafkaConsumer<>(config.sourceConsumerConfig(),
            new ByteArrayDeserializer(), new ByteArrayDeserializer());
        offsetSyncTopicPartition = new TopicPartition(config.offsetSyncTopic(), 0);
        consumer.assign(Collections.singleton(offsetSyncTopicPartition));
    }

    // for testing
    OffsetSyncStore(KafkaConsumer<byte[], byte[]> consumer, TopicPartition offsetSyncTopicPartition) {
        this.consumer = consumer;
        this.offsetSyncTopicPartition = offsetSyncTopicPartition;
    }

    long translateDownstream(TopicPartition sourceTopicPartition, long upstreamOffset) {
        OffsetSync offsetSync = latestOffsetSync(sourceTopicPartition);
        if (offsetSync.upstreamOffset() > upstreamOffset) {
            // Offset is too far in the past to translate accurately
            return -1;
        }
        long upstreamStep = upstreamOffset - offsetSync.upstreamOffset();
        return offsetSync.downstreamOffset() + upstreamStep;
    }

    // poll and handle records
    synchronized void update(Duration pollTimeout) throws InterruptedException {
        consumer.poll(pollTimeout).forEach(this::handleRecord);
    }

    void close() {
        // cleanup off-thread to prevent blocking
        new Thread(this::cleanup).start();
    }

    private synchronized void cleanup() {
        consumer.close();
    }

    protected void handleRecord(ConsumerRecord<byte[], byte[]> record) {
        OffsetSync offsetSync = OffsetSync.deserializeRecord(record);
        TopicPartition sourceTopicPartition = offsetSync.topicPartition();
        offsetSyncs.put(sourceTopicPartition, offsetSync);
    }

    private OffsetSync latestOffsetSync(TopicPartition topicPartition) {
        return offsetSyncs.computeIfAbsent(topicPartition, x -> new OffsetSync(topicPartition,
            -1, -1));
    }
}
