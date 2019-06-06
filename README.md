# flink 流处理源码分析

目前社区内没有对 flink 流处理源码解析的文章，本 repo 的目标是全方位细致解析 flink 流处理的实现原理，加深大家对 flink 流式处理的认识

本 repo 分析的 flink 版本为 1.7.2，预计将会从几篇系列文章或方面来展开:

* [flink 的安装](./docs/flink-install.md)
* [第一个 flink 流式处理小栗子](./docs/first-flink-example.md)
* [从源码角度简要分析一下 flink 流式处理小栗子](./docs/brief-analysis-of-first-flink-example.md)
* [flink 的 DataSource](./docs/flink-data-source.md)
* [flink 的 DataSink](./docs/flink-data-sink.md)
* [flink DataStream 的转换](./docs/flink-stream-transformation.md)
* [flink KeyedStream 的转换](./docs/flink-keyed-stream-transformation.md)
* [flink 中的时间](./docs/flink-time-characteristic.md)
* [flink 中的 StreamPartitioner](./docs/flink-stream-partitioner.md)
* [flink 中的异步操作符](./docs/flink-async-operator.md)
* [flink 中的定时器](./docs/flink-timer.md)
* [flink 的窗口 —— 窗口组件类](./docs/flink-window-component.md)
* [flink 的窗口 —— 窗口操作符](./docs/flink-window-operator.md)
* [flink 的窗口 —— 窗口函数](./docs/flink-window-function.md) 
* [flink 的窗口 —— 窗口流](./docs/flink-window-stream.md)
* [flink DataStream 依托窗口完成的操作（coGroup、join）](./docs/flink-coGroup-join.md)
* [flink KeyedStream 的 intervalJoin](./docs/flink-keyed-stream-intervaljoin.md)
* 👇 等待施工 🚧
* flink 的 StreamGraph
* flink 的 JobGraph
* flink 的 OperatorChain
* flink 的 StreamInputProcessor
* flink 的 Checkpoint
* flink 的 StreamTask
* flink 的 RecordWriter

另外，在 flink-runtime-src 目录中有我对 flink 流式处理逐行代码的详细分析，大家有兴趣的可以看看

如果大家觉得本 repo 对您有所帮助，可以点个 star

## 比较好的 flink 资料

* [flink 中文文档](http://flink-cn.shinonomelab.com/)
* [云邪的博客](http://wuchong.me/)
* [zhisheng的博客](http://www.54tianzhisheng.cn/)
* [flink 流操作官方栗子](https://github.com/apache/flink/tree/master/flink-examples/flink-examples-streaming)