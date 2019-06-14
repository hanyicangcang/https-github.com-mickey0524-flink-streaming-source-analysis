# flink 的 Checkpoint

flink 的检查点机制是 flink 容错的保障，flink 会定期生成快照，快照会存储 StreamOperator 当前的状态，当 flink 应用重启的时候，可以从之前生成的快照中恢复之前的状态。flink 的 Checkpoint 有两种工作机制 —— EXACTLY\_ONCE 和 AT\_LEAST\_ONCE，EXACTLY\_ONCE 机制保证一条流记录只被消费一次，AT\_LEAST\_ONCE 机制的流元素可能会被消费多次

## StreamTask 中与 Checkpoint 相关的部分

### 属性

```java
// 状态 backend，使用它来创建检查点流以及一个 keyed state backend
protected StateBackend stateBackend;

// 异步快照 workers 使用的线程池
private ExecutorService asyncOperationsThreadPool;
```

### triggerCheckpoint 方法

triggerCheckpoint 方法在 `StreamTask.java` 中有两个定义，`triggerCheckpoint(CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions)` 方法会被 TaskManager 调用，启用一个检查点

```java
public boolean triggerCheckpoint(CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions) throws Exception {
	CheckpointMetrics checkpointMetrics = new CheckpointMetrics()
			.setBytesBufferedInAlignment(0L)
			.setAlignmentDurationNanos(0L);

	return performCheckpoint(checkpointMetaData, checkpointOptions, checkpointMetrics);
}
```

另外一个 `triggerCheckpointOnBarrier(
			CheckpointMetaData checkpointMetaData,
			CheckpointOptions checkpointOptions,
			CheckpointMetrics checkpointMetrics)` 方法由 BarrierBuffer.java 和 BarrierTracker.java 调用，当 inputGate 中，所有的 channel 上某个检查点的 barrier 都到来的时候，调用此方法。[flink 的 CheckpointBarrierHandler](./docs/flink-checkpoint-barrier-handler.md)里有对 BarrierBuffer.java 和 BarrierTracker.java 何时调用 triggerCheckpointOnBarrier 的讲解，可以结合看一下
			
```java
public void triggerCheckpointOnBarrier(
		CheckpointMetaData checkpointMetaData,
		CheckpointOptions checkpointOptions,
		CheckpointMetrics checkpointMetrics) throws Exception {

	performCheckpoint(checkpointMetaData, checkpointOptions, checkpointMetrics);
}
```

### performCheckpoint 方法

前面两个 triggerCheckpoint 方法内部其实都调用了 performCheckpoint 方法

performCheckpoint 方法首先判断 StreamTask 是否处于运行状态，如果 isRunning 为 false 的话，会遍历 recordWriters 这个 list，广播检查点取消的事件，这里不能调用 Operator 的 broadcastCheckpointCancelMarker 方法，因为 OperatorChain 是在 invoke 方法里创建的，这里可能还没有创建

如果 isRunning 为 true 的话，首先调用 chain 上每一个操作符的 prepareSnapshotPreBarrier 方法，然后沿着 chain 广播检查点 barrier 给所有的操作符，最后调用 checkpointState 方法，拍摄状态快照

```java
private boolean performCheckpoint(
		CheckpointMetaData checkpointMetaData,
		CheckpointOptions checkpointOptions,
		CheckpointMetrics checkpointMetrics) throws Exception {
	synchronized (lock) {
		if (isRunning) {
			// 我们可以产生一个检查点
			// 从障碍和记录/水印/定时器/回调的角度来看，所有以下步骤都是作为原子步骤发生的

			// 步骤一：准备检查点，允许操作符做一些 pre-barrier 的工作
			operatorChain.prepareSnapshotPreBarrier(checkpointMetaData.getCheckpointId());

			// 步骤二：将 checkpoint barrier 传给下游
			operatorChain.broadcastCheckpointBarrier(
					checkpointMetaData.getCheckpointId(),
					checkpointMetaData.getTimestamp(),
					checkpointOptions);

			// 步骤三：拍摄状态快照。这应该在很大程度上是异步的，不会影响流式拓扑的进度
			checkpointState(checkpointMetaData, checkpointOptions, checkpointMetrics);
			return true;
		}
		else {
			// 我们无法执行检查点 - 让下游操作符知道他们不应该等待来自此操作符的任何输入，以免影响流式拓扑的进度
			// 我们不能操作符链上广播取消标记，因为它可能尚未创建，不能调用 operatorChain.broadcastCheckpointCancelMarker() 方法
			final CancelCheckpointMarker message = new CancelCheckpointMarker(checkpointMetaData.getCheckpointId());
		
			for (RecordWriter<SerializationDelegate<StreamRecord<OUT>>> recordWriter : recordWriters) {
				recordWriter.broadcastEvent(message);
			}

			return false;
		}
	}
}
```

### checkpointState

checkpointState 方法创建一个 CheckpointingOperation 实例，然后调用实例的 executeCheckpointing 方法

```java
private void checkpointState(
		CheckpointMetaData checkpointMetaData,
		CheckpointOptions checkpointOptions,
		CheckpointMetrics checkpointMetrics) throws Exception {
	// 生成检查点流工厂，主要用于 StateSnapshotContextSynchronousImpl.java 中生成检查点状态的输出流
	CheckpointStreamFactory storage = checkpointStorage.resolveCheckpointStorageLocation(
			checkpointMetaData.getCheckpointId(),
			checkpointOptions.getTargetLocation());

	CheckpointingOperation checkpointingOperation = new CheckpointingOperation(
		this,
		checkpointMetaData,
		checkpointOptions,
		storage,
		checkpointMetrics);

	checkpointingOperation.executeCheckpointing();
}
```

### CheckpointingOperation

👇是 CheckpointingOperation 的代码，我们可以看到 executeCheckpointing 方法会对 chain 上的每一个操作符执行 checkpointStreamOperator 方法，checkpointStreamOperator 会调用操作符的 snapshotState 方法，snapshotState 方法用于拍摄 KeyedState、OperatorState、OperatorStateManage 以及 KeyedStateManage 四个状态的快照，会对应生成四个 future 放入 OperatorSnapshotFutures，最后得到操作符 id 对 OperatorSnapshotFutures 实例的映射（操作符相关的方法会在后文提到）

然后创建 AsyncCheckpointRunnable 实例，AsyncCheckpointRunnable 类实现了 Runnable 接口，用来异步获取 snapshotState 中生成的四个状态快照，executeCheckpointing 方法会将 AsyncCheckpointRunnable 实例丢入线程池中执行

```java
private static final class CheckpointingOperation {

	private final StreamTask<?, ?> owner;

	private final CheckpointMetaData checkpointMetaData;
	private final CheckpointOptions checkpointOptions;
	private final CheckpointMetrics checkpointMetrics;
	private final CheckpointStreamFactory storageLocation;

	private final StreamOperator<?>[] allOperators;

	private long startSyncPartNano;
	private long startAsyncPartNano;

	// ------------------------

	private final Map<OperatorID, OperatorSnapshotFutures> operatorSnapshotsInProgress;

	public CheckpointingOperation(
			StreamTask<?, ?> owner,
			CheckpointMetaData checkpointMetaData,
			CheckpointOptions checkpointOptions,
			CheckpointStreamFactory checkpointStorageLocation,
			CheckpointMetrics checkpointMetrics) {

		this.owner = Preconditions.checkNotNull(owner);
		this.checkpointMetaData = Preconditions.checkNotNull(checkpointMetaData);
		this.checkpointOptions = Preconditions.checkNotNull(checkpointOptions);
		this.checkpointMetrics = Preconditions.checkNotNull(checkpointMetrics);
		this.storageLocation = Preconditions.checkNotNull(checkpointStorageLocation);
		this.allOperators = owner.operatorChain.getAllOperators();
		this.operatorSnapshotsInProgress = new HashMap<>(allOperators.length);
	}

	public void executeCheckpointing() throws Exception {
		
		// 执行每个操作符的 snapshotState 方法
		for (StreamOperator<?> op : allOperators) {
			checkpointStreamOperator(op);
		}

		checkpointMetrics.setSyncDurationMillis((startAsyncPartNano - startSyncPartNano) / 1_000_000);

		// 我们正在通过 snapshotInProgressList 将所有权转移到线程，在提交时激活
		AsyncCheckpointRunnable asyncCheckpointRunnable = new AsyncCheckpointRunnable(
			owner,
			operatorSnapshotsInProgress,
			checkpointMetaData,
			checkpointMetrics,
			startAsyncPartNano);

		owner.cancelables.registerCloseable(asyncCheckpointRunnable);  // 注册 close
		owner.asyncOperationsThreadPool.submit(asyncCheckpointRunnable);  // 提交到线程池
	}

	@SuppressWarnings("deprecation")
	// 执行每个操作符的 snapshotState 方法
	private void checkpointStreamOperator(StreamOperator<?> op) throws Exception {
		if (null != op) {

			OperatorSnapshotFutures snapshotInProgress = op.snapshotState(
					checkpointMetaData.getCheckpointId(),
					checkpointMetaData.getTimestamp(),
					checkpointOptions,
					storageLocation);
			operatorSnapshotsInProgress.put(op.getOperatorID(), snapshotInProgress);
		}
	}

	// 异步检查点状态
	private enum AsyncCheckpointState {
		RUNNING,
		DISCARDED,
		COMPLETED
	}
}
```

### AsyncCheckpointRunnable

AsyncCheckpointRunnable 实现了 Runnable 接口，执行 StreamTask 的所有的 state 快照的异步部分，主要用于四个 state 快照对应的 future 执行完毕之后，将状态快照发送给 TaskManager，直接来看 run 方法

可以看到，run 方法遍历 `CheckpointingOperation.executeCheckpointing` 方法中生成的 operatorSnapshotsInProgress，OperatorSnapshotFinalizer 实例会等待四个 state 快照 future 执行完毕，当全部执行完毕后，执行 reportCompletedSnapshotStates 方法，告诉 TaskManager 本 StreamTask 中所有操作符的检查点快照生成好了
 
```java
TaskStateSnapshot jobManagerTaskOperatorSubtaskStates =
		new TaskStateSnapshot(operatorSnapshotsInProgress.size());

TaskStateSnapshot localTaskOperatorSubtaskStates =
	new TaskStateSnapshot(operatorSnapshotsInProgress.size());

for (Map.Entry<OperatorID, OperatorSnapshotFutures> entry : operatorSnapshotsInProgress.entrySet()) {

	OperatorID operatorID = entry.getKey();
	OperatorSnapshotFutures snapshotInProgress = entry.getValue();

	// 通过执行所有快照可运行来完成所有异步部分
	OperatorSnapshotFinalizer finalizedSnapshots =
		new OperatorSnapshotFinalizer(snapshotInProgress);

	jobManagerTaskOperatorSubtaskStates.putSubtaskStateByOperatorID(
		operatorID,
		finalizedSnapshots.getJobManagerOwnedState());

	localTaskOperatorSubtaskStates.putSubtaskStateByOperatorID(
		operatorID,
		finalizedSnapshots.getTaskLocalState());
}

// 全部执行完毕，更新状态
if (asyncCheckpointState.compareAndSet(CheckpointingOperation.AsyncCheckpointState.RUNNING,
	CheckpointingOperation.AsyncCheckpointState.COMPLETED)) {

	reportCompletedSnapshotStates(
		jobManagerTaskOperatorSubtaskStates,
		localTaskOperatorSubtaskStates,
		asyncDurationMillis);
}
```

## StreamOperator 中与 Checkpoint 相关的部分

前文说到，`StreamTask.java` 的 CheckpointingOperation 实例会调用 OperatorChain 上所有操作符的 snapshotState 方法，今天我们来看看 `AbstractStreamOperator.java` 的 snapshotState 的方法

### snapshotState

在 `AbstractStreamOperator.java` 中，定义了两个 snapshotState 方法，使用重载实现，我们分别看看

#### snapshotState(long checkpointId, long timestamp, CheckpointOptions checkpointOptions, CheckpointStreamFactory factory)

`StreamTask.java` 调用的就是这个方法，我们在前文讲到，操作符快照由 4 个 state 快照组成，本方法开启 4 个 state 快照的生成，将其用 Future 包装后写入 OperatorSnapshotFutures，在 AsyncCheckpointRunnable 中会对所有操作符的 OperatorSnapshotFutures 实例统一处理

```java
// 获取 KeyGroupRange
KeyGroupRange keyGroupRange = null != keyedStateBackend ?
		keyedStateBackend.getKeyGroupRange() : KeyGroupRange.EMPTY_KEY_GROUP_RANGE;
		
// 用于装载各种 Future 对象
OperatorSnapshotFutures snapshotInProgress = new OperatorSnapshotFutures();

StateSnapshotContextSynchronousImpl snapshotContext = new StateSnapshotContextSynchronousImpl(
	checkpointId,
	timestamp,
	factory,
	keyGroupRange,
	getContainingTask().getCancelables())) {

snapshotState(snapshotContext);
	
// 设置 keyedState 输出流的 Future 对象
snapshotInProgress.setKeyedStateRawFuture(snapshotContext.getKeyedStateStreamFuture());
// 设置 operatorState 输出流的 Future 对象
snapshotInProgress.setOperatorStateRawFuture(snapshotContext.getOperatorStateStreamFuture());
	
// 如果 operatorStateBackend 不为空的时候，设置操作符状态管理 Future
if (null != operatorStateBackend) {
	snapshotInProgress.setOperatorStateManagedFuture(
		operatorStateBackend.snapshot(checkpointId, timestamp, factory, checkpointOptions));
}

// 如果 keyedStateBackend 不为空的时候，设置 keyedStateBackend Future
if (null != keyedStateBackend) {
	snapshotInProgress.setKeyedStateManagedFuture(
		keyedStateBackend.snapshot(checkpointId, timestamp, factory, checkpointOptions));
}

return snapshotInProgress;
```

代码中出现的一些类就不展开讲解了，这里介绍类的作用，这些类的代码都位于 `org.apache.flink.runtime.state` 目录，感兴趣的同学可以去看看，本 repo 的 flink-runtime-src 目录里也有对这些类的解析

* KeyGroupRange：定义一系列 key 的索引，用来区分每一个 key
* OperatorSnapshotFutures：用来存放四个 state 快照的 future
* StateSnapshotContextSynchronousImpl：用于创建读写 KeyedState 和 OperatorState 的流

#### snapshotState(StateSnapshotContext context)

本 snapshotState 方法在之前的 snapshotState 方法中被调用，用于将操作符的所有定时器写入 KeyedStateCheckpointOutputStream 进行持久化, KeyedStateCheckpointOutputStream 从 StateSnapshotContextSynchronousImpl 中获取

```java
KeyedStateCheckpointOutputStream out = context.getRawKeyedOperatorStateOutput();

KeyGroupsList allKeyGroups = out.getKeyGroupList();  // 获取全部的 key-group
for (int keyGroupIdx : allKeyGroups) {
	out.startNewKeyGroup(keyGroupIdx);  // 开始当前 key group 的写入

	timeServiceManager.snapshotStateForKeyGroup(
		new DataOutputViewStreamWrapper(out), keyGroupIdx);
}
```

## 定时器中与 Checkpoint 相关的部分

Checkpoint 会将定时器的 ts、key 和命名空间序列化到 KeyedState 的流中。AbstractStreamOperator 的 snapshotState 方法调用了 `timeServiceManager.snapshotStateForKeyGroup` 方法，snapshotStateForKeyGroup 创建了一个 InternalTimerServiceSerializationProxy 实例，调用了 InternalTimerServiceSerializationProxy 的 write 方法（也在下方给出）

我们知道，在 timerServicesManager 中维护着一个 hashMap，k 为定时器服务的名称，v 为定时器服务实例，首先调用 getRegisteredTimerServices 方法获取这个 hashMap，将 timerServicesManager 中定时器服务的个数写入流，然后遍历 hashMap，将每个定时器服务的名称和序列化后的定时器服务实例写入流，完成定时器的持久化，InternalTimersSnapshotReaderWriters 是用来序列化和反序列化定时器实例的

```java
public void snapshotStateForKeyGroup(DataOutputView stream, int keyGroupIdx) throws IOException {
	Preconditions.checkState(useLegacySynchronousSnapshots);
	InternalTimerServiceSerializationProxy<K> serializationProxy =
		new InternalTimerServiceSerializationProxy<>(this, keyGroupIdx);

	serializationProxy.write(stream);
}

public void write(DataOutputView out) throws IOException {
	// 写入版本信息
	super.write(out);
	// 从 timerServicesManager 中获取所有的定时器服务
	final Map<String, InternalTimerServiceImpl<K, ?>> registeredTimerServices =
		timerServicesManager.getRegisteredTimerServices();

	// 写入时间服务个数
	out.writeInt(registeredTimerServices.size());
	for (Map.Entry<String, InternalTimerServiceImpl<K, ?>> entry : registeredTimerServices.entrySet()) {
		String serviceName = entry.getKey();
		InternalTimerServiceImpl<K, ?> timerService = entry.getValue();

		out.writeUTF(serviceName);  // 写入定时器名称
		// 将进程时间定时器和事件时间定时器写入快照
		InternalTimersSnapshotReaderWriters
			.getWriterForVersion(
				VERSION,
				timerService.snapshotTimersForKeyGroup(keyGroupIdx),
				timerService.getKeySerializer(),
				(TypeSerializer) timerService.getNamespaceSerializer())
			.writeTimersSnapshot(out);
	}
}
```

### timerService.snapshotTimersForKeyGroup

定时器服务中有两个队列，分别用来存储进程时间定时器和事件时间定时器，
timerService.snapshotTimersForKeyGroup 调用 队列的 getSubsetForKeyGroup 方法获取定时器服务中和 keyGroupIdx 相关的定时器，然后通过获取的定时器子集、key序列器 和 namespace 序列器生成 InternalTimersSnapshot 实例，InternalTimersSnapshot 会创建 key 序列器和 namespace 序列器的快照，这样在之后的快照恢复中，可以通过序列器快照恢复序列器，再通过序列器恢复 key 和 namespace

```java
public InternalTimersSnapshot<K, N> snapshotTimersForKeyGroup(int keyGroupIdx) {
	return new InternalTimersSnapshot<>(
		keySerializer,
		namespaceSerializer,
		eventTimeTimersQueue.getSubsetForKeyGroup(keyGroupIdx),
		processingTimeTimersQueue.getSubsetForKeyGroup(keyGroupIdx));
}

// 当生成定时器快照的时候使用的构造器
public InternalTimersSnapshot(
		TypeSerializer<K> keySerializer,
		TypeSerializer<N> namespaceSerializer,
		@Nullable Set<TimerHeapInternalTimer<K, N>> eventTimeTimers,
		@Nullable Set<TimerHeapInternalTimer<K, N>> processingTimeTimers) {

	Preconditions.checkNotNull(keySerializer);
	this.keySerializerSnapshot = TypeSerializerUtils.snapshotBackwardsCompatible(keySerializer);
	Preconditions.checkNotNull(namespaceSerializer);
	this.namespaceSerializerSnapshot = TypeSerializerUtils.snapshotBackwardsCompatible(namespaceSerializer);

	this.eventTimeTimers = eventTimeTimers;
	this.processingTimeTimers = processingTimeTimers;
}
```

### InternalTimersSnapshotReaderWriters

InternalTimersSnapshotReaderWriters 提供了 Reader 和 Writer，用于读取和存储定时器服务快照，从下方代码中，可以清晰的看到，writeTimersSnapshot 先将 key 和 namespace 序列器快照写入流，然后依次将所有的定时器的 key、namespace 和 ts 写入流，完成持久化

```java
private abstract static class AbstractInternalTimersSnapshotWriter<K, N> implements InternalTimersSnapshotWriter {

	protected final InternalTimersSnapshot<K, N> timersSnapshot;

	protected final TypeSerializer<K> keySerializer;
	protected final TypeSerializer<N> namespaceSerializer;

	public AbstractInternalTimersSnapshotWriter(
			InternalTimersSnapshot<K, N> timersSnapshot,
			TypeSerializer<K> keySerializer,
			TypeSerializer<N> namespaceSerializer) {
		this.timersSnapshot = checkNotNull(timersSnapshot);
		this.keySerializer = checkNotNull(keySerializer);
		this.namespaceSerializer = checkNotNull(namespaceSerializer);
	}

	// 将 key 和命名空间的序列器写入快照
	protected abstract void writeKeyAndNamespaceSerializers(DataOutputView out) throws IOException;

	@Override
	public final void writeTimersSnapshot(DataOutputView out) throws IOException {
		writeKeyAndNamespaceSerializers(out);

		LegacyTimerSerializer<K, N> timerSerializer = new LegacyTimerSerializer<>(
			keySerializer,
			namespaceSerializer);

		// write 事件时间定时器
		Set<TimerHeapInternalTimer<K, N>> eventTimers = timersSnapshot.getEventTimeTimers();
		if (eventTimers != null) {
			out.writeInt(eventTimers.size());  // 将事件时间定时器的数量写入快照
			for (TimerHeapInternalTimer<K, N> eventTimer : eventTimers) {
				timerSerializer.serialize(eventTimer, out);
			}
		} else {
			out.writeInt(0);
		}

		// write 进程时间定时器
		Set<TimerHeapInternalTimer<K, N>> processingTimers = timersSnapshot.getProcessingTimeTimers();
		if (processingTimers != null) {
			out.writeInt(processingTimers.size());  // 将进程时间定时器的数量写入快照
			for (TimerHeapInternalTimer<K, N> processingTimer : processingTimers) {
				timerSerializer.serialize(processingTimer, out);
			}
		} else {
			out.writeInt(0);
		}
	}
}

private static class InternalTimersSnapshotWriterV2<K, N> extends AbstractInternalTimersSnapshotWriter<K, N> {

	public InternalTimersSnapshotWriterV2(
			InternalTimersSnapshot<K, N> timersSnapshot,
			TypeSerializer<K> keySerializer,
			TypeSerializer<N> namespaceSerializer) {
		super(timersSnapshot, keySerializer, namespaceSerializer);
	}

	@Override
	protected void writeKeyAndNamespaceSerializers(DataOutputView out) throws IOException {
		TypeSerializerSnapshot.writeVersionedSnapshot(out, timersSnapshot.getKeySerializerSnapshot());
		TypeSerializerSnapshot.writeVersionedSnapshot(out, timersSnapshot.getNamespaceSerializerSnapshot());
	}
}

...

public void serialize(TimerHeapInternalTimer<K, N> record, DataOutputView target) throws IOException {
	keySerializer.serialize(record.getKey(), target);
	namespaceSerializer.serialize(record.getNamespace(), target);
	LongSerializer.INSTANCE.serialize(record.getTimestamp(), target);
}
```

## 总结

今天这篇文章我们介绍了 flink 的检查点，checkpoint 还是比较难理解的，而且涉及的类比较多，大家可以细看看，希望对大家有所帮助
