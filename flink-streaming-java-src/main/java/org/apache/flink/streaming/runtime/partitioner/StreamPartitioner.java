/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.partitioner;

import org.apache.flink.annotation.Internal;
import org.apache.flink.runtime.io.network.api.writer.ChannelSelector;
import org.apache.flink.runtime.plugable.SerializationDelegate;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;

import java.io.Serializable;

/**
 * A special {@link ChannelSelector} for use in streaming programs.
 */
/**
 * 流程序中使用的一种 ChannelSelector
 */
@Internal
public abstract class StreamPartitioner<T> implements
		ChannelSelector<SerializationDelegate<StreamRecord<T>>>, Serializable {
	private static final long serialVersionUID = 1L;

	protected int numberOfChannels;
	
	// setup 方法在 RecordWriter 初始化的时候被设置
	@Override
	public void setup(int numberOfChannels) {
		this.numberOfChannels = numberOfChannels;
	}

	@Override
	public boolean isBroadcast() {
		return false;
	}

	public abstract StreamPartitioner<T> copy();
}
