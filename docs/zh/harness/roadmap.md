# roadmap (暂定)


## examples

1. claw类型的应用

## enhance

1. harness的大部分的机制和能力都是通过hook机制来注入的, 用户插入的hook可能会破坏我们默认的hook执行顺序,队列需要分级, 分为internal和external, 前者的优先级高于后者, 用户的hook默认是在external队列中, 另外提供接口来注入到internal队列中, 将两边隔离
2. 对于harness的构建方法, 支持直接将agent传入, 这样用户能复用过去的reactAgent的代码, 避免重复配置, 最小成本化升级
3. 和core那边的统一化, 避免用户迷惑

## feature

1. 按照生命周期/作用范围/权限/内部机制等来分类记忆类型, 而不是根据业务语义来分类
2. 网关层的构建, 需不需要放在harness层, 如何以灵活的方式让agent能根据skill来自己将某个IM挂载到agent上
3. agent请求human进行review的callback function入口


## robustness

1. 补齐UT
2. refactor Result类