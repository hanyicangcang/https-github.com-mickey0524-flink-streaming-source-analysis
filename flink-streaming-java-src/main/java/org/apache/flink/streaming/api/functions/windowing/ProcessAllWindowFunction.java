/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.functions.windowing;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.functions.AbstractRichFunction;
import org.apache.flink.api.common.state.KeyedStateStore;
import org.apache.flink.streaming.api.windowing.windows.Window;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

/**
 * Base abstract class for functions that are evaluated over non-keyed windows using a context
 * for retrieving extra information.
 *
 * @param <IN> The type of the input value.
 * @param <OUT> The type of the output value.
 * @param <W> The type of {@code Window} that this window function can be applied on.
 */
/**
 * 使用上下文获取额外信息，在非 key 化窗口上计算的函数的基本抽象类
 */
@PublicEvolving
public abstract class ProcessAllWindowFunction<IN, OUT, W extends Window> extends AbstractRichFunction {

	private static final long serialVersionUID = 1L;

	/**
	 * Evaluates the window and outputs none or several elements.
	 *
	 * @param context The context in which the window is being evaluated.
	 * @param elements The elements in the window being evaluated.
	 * @param out A collector for emitting elements.
	 *
	 * @throws Exception The function may throw exceptions to fail the program and trigger recovery.
	 */
	/**
	 * 计算窗口，然后输出零个或多个元素
	 */
	public abstract void process(Context context, Iterable<IN> elements, Collector<OUT> out) throws Exception;

	/**
	 * Deletes any state in the {@code Context} when the Window is purged.
	 *
	 * @param context The context to which the window is being evaluated
	 * @throws Exception The function may throw exceptions to fail the program and trigger recovery.
	 */
	/**
	 * 当窗口被清洗的时候，删除 Context 中的所有状态
	 */
	public void clear(Context context) throws Exception {}

	/**
	 * The context holding window metadata.
	 */
	/**
	 * 掌握窗口元数据的上下文
	 */
	public abstract class Context {
		/**
		 * @return The window that is being evaluated.
		 */
		/**
		 * 返回窗口是否正在被计算
		 */
		public abstract W window();

		/**
		 * State accessor for per-key and per-window state.
		 *
		 * <p><b>NOTE:</b>If you use per-window state you have to ensure that you clean it up
		 * by implementing {@link ProcessWindowFunction#clear(ProcessWindowFunction.Context)}.
		 */
		/**
		 * 每个 key，每个窗口的状态获取器
		 */
		public abstract KeyedStateStore windowState();

		/**
		 * State accessor for per-key global state.
		 */
		/**
		 * 每个 key 的全局状态获取器
		 */
		public abstract KeyedStateStore globalState();

		/**
		 * Emits a record to the side output identified by the {@link OutputTag}.
		 *
		 * @param outputTag the {@code OutputTag} that identifies the side output to emit to.
		 * @param value The record to emit.
		 */
		/**
		 * 侧边输出
		 */
		public abstract <X> void output(OutputTag<X> outputTag, X value);
	}
}
