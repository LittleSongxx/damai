# damai-pro 面试深度问答手册

> 本手册基于 damai-pro 项目实际代码编写，覆盖简历全部关键点，并延伸至 Spring Cloud Alibaba、Seata、ShardingSphere、Redis、RabbitMQ 等分布式系统核心八股文。  
> 每条问答带有 **加粗核心要点**，可直接用于面试准备。

---

## 一、项目架构与选型

### Q1：介绍一下大麦项目的整体架构

**Spring Cloud Alibaba 微服务架构**，核心服务包括：
- **damai-gateway-service**：网关层，鉴权 + Sentinel 限流 + API 日志采集
- **damai-program-service**：节目与座位管理，**抢票前置链路**的核心
- **damai-order-service**：订单服务，**Seata AT 分布式事务**协调者
- **damai-pay-service**：支付服务，对接支付渠道
- **damai-user-service**：用户服务，验证码与人机校验
- **damai-customize-service**：定制化服务，消息记录与异常补偿

服务间通过 **OpenFeign** 通信，**Nacos** 做注册中心与配置中心，**RabbitMQ** 异步削峰，**ShardingSphere** 分库分表。

### Q2：为什么选 Spring Cloud Alibaba 而不是 Spring Cloud Netflix？

- **Netflix 已进入维护模式**，Hystrix、Ribbon 等组件不再更新
- Alibaba 提供更完整的微服务生态：**Nacos（注册+配置一体化）、Sentinel（比 Hystrix 更强大）、Seata（分布式事务）**
- 与阿里云基础设施深度集成，便于后续上云

### Q3：为什么数据库分片选 ShardingSphere 而不是 MyCat 或 DBLE？

- **MyCat** 是独立中间件，引入额外部署和运维复杂度，且基于代理模式有性能损耗
- **DBLE** 基于 MyCat 改进，但仍需独立部署
- **ShardingSphere-JDBC** 以 SDK 形式嵌入应用，**零额外部署，无需网络中转**，性能损耗极低，且 JDBC 层兼容性好
- 支持**自定义分片算法**（本项目实现了基因法），扩展灵活

### Q4：Nacos 作为注册中心和配置中心的角色分别是什么？

- **注册中心**：服务启动时向 Nacos 注册，OpenFeign 通过服务名发现调用目标，支持健康检查
- **配置中心**：分片配置、Seata 配置等写入 Nacos，支持动态刷新，变更无需重启

---

## 二、选座抢票（Redis Lua + 分布式锁）

### Q5：选座抢票的完整链路是怎样的？

1. 用户选择节目、票档、座位 → 请求到达 **damai-program-service**
2. `ProgramOrderService` 先通过 **Redis Lua（programSeat.lua）** 读取座位状态（未售/锁定/已售）
3. 按**票档粒度加本地锁**，防止同一票档并发选座冲突
4. 通过 Lua 脚本 `programDataCreateOrderResolution.lua` 执行**座位状态迁移**（未售→锁定）
5. 生成订单号，通过 **RabbitMQ 发送异步建单消息**
6. 发送**延迟关单消息**（Redisson 延迟队列，防止未支付订单长期占用座位）
7. 返回锁定成功 → 用户完成支付

### Q6：为什么本地锁按票档粒度而不是按座位粒度？

**平衡并发度和锁竞争**：
- 按座位粒度：锁粒度过细，每个座位一个锁，**锁管理开销大**（一个节目可能有数千座位）
- 按节目粒度：锁粒度过粗，**并发度太低**，一个节目同时只能处理一个选座请求
- 按票档粒度：同一价格区间的座位共享一个锁，**并发度适中**——不同票档间完全并行，同一票档内串行，而同一票档的并发冲突恰是超卖风险所在

### Q7：Redis Lua 脚本中座位状态怎么流转？

三种座位状态存在不同的 Redis Hash Key 中：
- `seat_no_sold_resolution_hash_key`：**未售**座位
- `seat_lock_resolution_hash_key`：**已锁定**（待支付）
- `seat_sold_resolution_hash_key`：**已售**

订单支付时：锁定 → 已售（`OrderProgramDataResolution.lua` 处理，109 行 Lua，包含票档库存调整 + 流水记录）  
订单取消/超时：锁定 → 未售（同一 Lua 脚本，恢复库存并记录流水）

### Q8：Redisson 分布式锁在项目中具体怎么用？锁的粒度是什么？

通过 `@ServiceLock` 注解声明式加锁，AOP 切面实现。例如订单取消操作：
- **key**：`LOCK_ORDER + orderNumber`
- **锁粒度**：按**订单号**加锁，确保同一订单的并发操作（支付回调 + 用户取消 + 超时取消）串行执行
- **锁类型**：支持 **ReentrantLock、FairLock、ReadWriteLock**（通过 `LockType` 枚举选择）

### Q9：Redisson 分布式锁的看门狗机制是什么？

Redisson 默认锁租约 30 秒。如果业务逻辑超过 30 秒，**看门狗（Watchdog）** 每 10 秒自动续期 30 秒。业务完成后调用 `unlock()` 停止续期。避免了**锁因业务执行时间过长而过期**导致的问题。

### Q10：Redis 分布式锁和本地锁分别解决了什么问题？

- **本地锁**（票档粒度）：解决**同一服务实例内**同一票档的并发冲突，性能高（JVM 级别，无网络开销）
- **Redisson 分布式锁**（订单粒度）：解决**跨服务实例**的状态互斥（多个 order-service 实例可能同时处理同一订单的状态变更）

本地锁 + 分布式锁是**两层防护**，而非替代关系。

---

## 三、异步一致性（RabbitMQ + Seata AT）

### Q11：为什么用 RabbitMQ 而不是直接同步调 order-service 接口？

**削峰填谷**：秒杀/抢票场景下，下单请求会瞬间暴涨。同步调用直接穿透到 order-service → DB，DB 写入压力无法承受。通过 RabbitMQ 将请求**缓冲到队列**，order-service 按自己的消费能力逐步消费，**保护 DB 不被击垮**。

### Q12：RabbitMQ 消息的发送确认机制怎么实现的？

`CreateOrderSend.sendMessage()` 使用 **Publisher Confirm** 模式：
1. 发送消息时设置 `CorrelationData`（UUID）
2. `correlationData.getFuture()` 异步等待 Broker 确认
3. **confirm.isAck()**：发送成功，执行 `successCallback`
4. **confirm.isNack() 或异常**：发送失败，执行 `failureCallback` → **回滚 Redis 座位状态**

### Q13：Seata AT 模式在项目中怎么用的？

消费端 `OrderService.createMq()` 方法标注 `@GlobalTransactional`，统筹以下操作：
- **d_order 表**：写入订单主表（分片2库4表）
- **d_order_ticket_user 表**：写入购票人信息（分片）
- **d_order_program 表**：写入订单-节目关联
- **节目库扣减**：通过 OpenFeign 调用 program-service 的 `ReduceRemainNumber` 接口

Seata AT 的 **undo_log** 表记录所有数据变更的前镜像/后镜像，任何一步失败时自动回滚。

### Q14：Seata AT 的工作原理（两阶段提交增强版）？

**一阶段**：Seata 代理数据源，拦截 SQL
- 执行 SQL 前保存**前镜像**（UNDO_LOG）
- 执行 SQL
- 执行 SQL 后保存**后镜像**（UNDO_LOG）
- 生成行锁

**二阶段**：
- **提交**：异步删除 UNDO_LOG（因为一阶段已提交本地事务，无需额外操作）
- **回滚**：通过 UNDO_LOG 的镜像数据生成反向 SQL 执行回滚

**AT 模式优势**：对业务代码**零侵入**，只需 `@GlobalTransactional` 注解。

### Q15：AT 模式有什么限制？什么场景不适合？

- **隔离性弱**：一阶段已提交，其他事务可能在二阶段完成前读到未最终确认的数据（Seata 通过全局锁 + `SELECT FOR UPDATE` 缓解）
- **不支持跨语言**：Java 专属
- **性能开销**：每条 SQL 需额外记录 UNDO_LOG（约 2-4 条额外 SQL）
- **不适合高并发热点数据**：全局锁会成为瓶颈（本项目通过**分库分表**将热点数据打散）

### Q16：为什么选择 AT 模式而不是 TCC？

- 票务订单场景的**业务操作本质是 CRUD**，AT 模式天然适合
- **TCC 需要业务代码实现 Try/Confirm/Cancel 三个接口**，开发成本高
- AT 的 `@GlobalTransactional` 一行注解即完成，开发效率高
- 但本项目也保留了 TCC 的扩展空间（支付服务可能更适合 TCC）

### Q17：超时订单怎么关单？延迟队列怎么实现的？

1. 抢票成功后 `DelayOrderCancelSend` 将**订单号写入 Redisson 延迟队列**，延迟时间 = 支付倒计时（如 15 分钟）
2. 到期后 `DelayOrderCancelConsumer` 消费消息，调用 `OrderService.cancel()`
3. cancel 方法：
   - 通过 Lua 脚本 `OrderProgramDataResolution.lua` 将锁定座位 → 未售，恢复票档库存
   - 更新 DB 订单状态为已取消
   - 记录操作流水
4. 消费幂等：通过 `messageId` 查询消费记录表，避免重复关单

### Q18：消息消费超时怎么处理的？

`CreateOrderConsumer.beforeConsume()` 计算**消息延迟 = 当前时间 - 消息生产时间**。超过 **5 秒** 阈值则：
1. 不再创建订单（避免用户已超时等待）
2. 通过 Lua 脚本 **回滚 Redis 座位状态**
3. 写入 **DISCARD_ORDER 列表**（Redis）供后续对账
4. 记录 Prometheus 指标 `damai_order_create_fail_total`

### Q19：对账机制是怎么做的？覆盖哪些维度？

三类对账：
1. **节目-订单对账**（`ReconciliationTask`）：对比 `ProgramRecordTask` 记录与订单实际状态，发现不一致则标记异常并修复
2. **消息对账**（`customize-service`）：对比 `MessageProducerRecord` 与 `MessageConsumerRecord`，确保消息不丢
3. **操作流水对账**（`OrderProgramDataResolution.lua` 中记录）：Redis Hash 中记录了每次座位状态变更的完整快照

对账定时执行（默认 3 分钟一次），通过 `BusinessThreadPool` 异步处理，不阻塞主流程。

---

## 四、分片扩容（ShardingSphere 基因法）

### Q20：什么是基因法分片？为什么订单表用基因法？

**核心思想**：订单号生成时，将 userId 的**后 6 位**嵌入订单号。这样订单号和 userId 的低位相同，**无论按订单号还是 userId 查询，都能精确定位到同一分片**，无需广播查询。

本项目基因位分布（2库4表）：
```
userId 后 6 位（bit5-bit0）：[b5][b4][b3][b2][b1][b0]
                       表基因（低 log2(4)=2 位）：[b1][b0]
                       库基因（跳过表基因后 1 位）：[b2]
```

### Q21：分库算法和分表算法的具体计算过程？

**表路由**（`TableOrderComplexGeneArithmetic`）：
```
tableIndex = (tableCount - 1) & userId  // 取低 N 位，N=log2(tableCount)
```

**库路由**（`DatabaseOrderComplexGeneArithmetic`）：
```
tableGeneLength = log2(tableCount)
databaseIndex = (databaseCount - 1) & (userId >> tableGeneLength)  // 右移跳过表基因位
```

示例（2库4表，userId 后6位 = 13 = 0b001101）：
- tableIndex = 3 & 13 = 1 → `d_order_1` 表
- databaseIndex = 1 & (13 >> 2) = 1 & 3 = 1 → `ds_1` 库

### Q22：基因法的扩展性如何？从 2库4表 扩到 4库8表 怎么办？

通过配置驱动，只需修改 yaml：
```yaml
# 扩容前
databaseOrderComplexGeneArithmetic:
  props:
    sharding-count: 2     # 改为 4
    table-sharding-count: 4 # 改为 8
```

**数据迁移**：`damai-migrate-service` 模块负责。通过 **Hint 路由**强制指定目标分片，先双写再切换读，最后清理旧数据。基因法保证了扩容后**老订单号仍能精确定位**（高位扩展不影响低位基因位）。

### Q23：ShardingSphere 下 Seata AT 怎么协作？

ShardingSphere 的 `ShardingSphereTransactionBaseSeataAtConfiguration` 负责**将 Seata 的 DataSource 代理注入 ShardingSphere**。Seata 在物理 DataSource 层拦截，ShardingSphere 在逻辑 DataSource 层路由，两者在**不同层面**工作，互不冲突。

### Q24：不分片键的查询怎么处理？

`doSharding` 方法中，如果 `columnNameAndShardingValuesMap` 为空（即查询条件无分片键），返回 `allActualSplitDatabaseNames` → **广播查询所有分片**。这种做法性能差，因此业务代码中要求按分片键查询（订单号或 userId），非分片键查询走 ES。

---

## 五、Sentinel / 网关限流 / 验证码

### Q25：Sentinel 在项目中用了哪些规则？

- **网关层**：`GatewaySentinelConfiguration` 配置了针对下单/支付接口的 **QPS 限流**，超过阈值返回降级响应
- **服务层**：`damai-ai` 中的 `SentinelRuleInitializer` 为 AI 调用配置熔断规则
- **API 限流 Lua 脚本**（`apiLimit.lua`）：网关层使用 Redis Lua 对 IP/接口维度做滑动窗口限流

### Q26：Sentinel 的限流算法有哪些？你项目中用的是哪种？

Sentinel 支持：
- **直接拒绝**（默认）：QPS 超过阈值立即拒绝
- **Warm Up**：预热/冷启动，缓慢增加通过量
- **匀速排队**：漏桶算法，请求匀速通过

本项目网关层用**直接拒绝模式**，简单有效。AI 调用层用**熔断降级**，异常比例超过阈值时触发。

### Q27：Sentinel 控制台 vs 代码配置怎么选？

**控制台**适合动态调整规则，但数据存储在内存中，重启丢失。**代码配置**适合固定规则，随应用启动加载。本项目**两者结合**：核心限流规则用代码配置（`GatewaySentinelConfiguration`），临时调整可通过控制台。

### Q28：用户验证码系统怎么防刷？

- 验证码校验通过 **Redis Lua 脚本**（`checkNeedCaptcha.lua`）实现，记录每个 IP/用户的操作频率
- 超过阈值自动触发**验证码二次校验**
- 验证码框架（`damai-captcha-manage-framework`）支持拼图和点选两种模式

---

## 六、Seata / 分布式事务（八股文深度）

### Q29：Seata 的四种事务模式分别是什么？怎么选？

| 模式 | 原理 | 侵入性 | 性能 | 适用场景 |
|------|------|--------|------|---------|
| **AT** | undo_log 自动补偿 | 无 | 中 | 纯 CRUD 业务（本项目使用） |
| **TCC** | Try/Confirm/Cancel | 高 | 高 | 有复杂业务逻辑、需资源预留 |
| **Saga** | 正向+补偿链 | 中 | 高 | 长流程、老系统 |
| **XA** | 数据库 XA 协议 | 无 | 低 | 强一致性要求 |

### Q30：Seata AT 的全局锁会有什么问题？怎么优化？

全局锁在 **一阶段提交前获取**，二阶段完成后释放。热点数据（如同一节目的同一票档）的全局锁竞争会导致**串行化执行**。

本项目优化手段：
1. **分库分表**：订单数据按 userId 分散到多库多表，**热点数据自然打散**
2. **前置 Lua 扣减**：库存扣减在 Redis 中原子完成（无全局锁），DB 仅做最终确认
3. **异步削峰**：RabbitMQ 串行消费，减少并发冲突

---

## 七、Spring Cloud Alibaba（八股文）

### Q31：OpenFeign 的底层原理？

OpenFeign 基于**动态代理 + HttpURLConnection**（或替换为 OkHttp/HttpClient）：
1. `@FeignClient` 注解标注的接口 → 生成动态代理对象
2. 方法调用时，根据注解配置（URL、请求方式、参数等）构建 HTTP 请求
3. 通过 **Ribbon/LoadBalancer** 实现客户端负载均衡，从 Nacos 获取服务实例列表
4. 发送请求并解析响应，反序列化为返回值

### Q32：Gateway 和 Nginx 有什么区别？

- **Nginx**：反向代理，C 语言实现，高性能七层负载均衡，适合**入口网关**
- **Spring Cloud Gateway**：API 网关，Java 实现，基于 WebFlux + Reactor 异步非阻塞，适合**微服务内部路由**，可与 Sentinel/Nacos 深度集成

本项目 Nginx 在最外层，Gateway 作为微服务统一入口。

### Q33：Spring Cloud Gateway 的三大核心组件？

- **Route（路由）**：ID + 目标 URI + Predicate 集合 + Filter 集合
- **Predicate（断言）**：匹配 HTTP 请求的条件（路径、Header、参数等）
- **Filter（过滤器）**：对请求/响应做修改（鉴权、限流、日志等）

---

## 八、Redis 进阶（八股文）

### Q34：Redis 的 Lua 脚本执行时，其他命令会被阻塞吗？

**会**。Redis 是单线程执行命令，Lua 脚本执行期间**整个 Redis 实例被阻塞**，其他客户端的命令无法执行。因此 Lua 脚本必须**短小精悍**，避免长时间执行。

本项目 Lua 脚本最长 109 行（`OrderProgramDataResolution.lua`），但仍然控制在毫秒级完成，因为全是内存操作。

### Q35：Redis 事务（MULTI/EXEC）和 Lua 脚本有什么区别？

- **Redis 事务**：命令只是入队，EXEC 时才原子执行，但**不支持逻辑判断**（无法根据 GET 的值决定是否 SET）
- **Lua 脚本**：图灵完备，支持条件判断、循环、变量计算，真正实现**原子化的业务逻辑**

秒杀/抢票场景必须用 Lua——需要先检查库存再扣减，这是事务做不到的。

---

## 九、MySQL / 数据库（八股文）

### Q36：分库分表后，跨分片的分页查询怎么处理？

本项目通过以下策略避免跨分片分页：
- **按分片键查询**（订单号/userId）是第一优先级
- 非分片键查询走 **Elasticsearch**（`damai-elasticsearch-framework`）
- 管理后台的复杂查询使用**归并排序**：各分片返回 TOP N，应用层合并

### Q37：使用 ShardingSphere 后，自增 ID 还能用吗？

不能。ShardingSphere 分片环境下的自增 ID 会造成**不同分片出现相同 ID**。本项目使用**百度 UidGenerator（Snowflake 算法）** 生成全局唯一订单号，并通过基因法确保订单号中嵌入 userId 基因。

---

## 十、Elasticsearch（八股文）

### Q38：ES 在项目中做什么？

`damai-elasticsearch-framework` 封装了 ES 操作，用于**节目/演出的全文检索**。分片键（order_number/user_id）查询走 ShardingSphere 精确路由，模糊搜索/条件筛选走 ES。

---

## 十一、系统设计 / 场景题

### Q39：如果抢票时 Redis 挂了怎么办？

- **Redis Cluster** 主从 + 哨兵自动故障转移
- 降级策略：Redis 不可用时，**直接拒绝选座请求**（宁可少卖不可超卖），提示用户稍后重试
- 预热阶段检查 Redis 健康状态
- 多级缓存（Caffeine 本地缓存）兜底部分查询

### Q40：如何保证下单和支付的最终一致性？

1. **正常路径**：下单 → MQ 异步建单 → 支付 → 支付回调更新订单状态 → 座位锁定→已售
2. **超时未支付**：延迟队列触发关单 → 回滚 Redis 座位 + DB 订单状态
3. **支付回调丢失**：定时对账补单，对比支付渠道流水与本地订单状态
4. **MQ 消息丢失**：对账发现异常订单 → 人工介入或自动补偿

### Q41：项目中你用到了哪些设计模式？

- **策略模式**：多种分片算法（基因法、取模法）可配置切换
- **模板方法**：`AbstractConsumerHandler` 定义了 beforeConsume → doConsume → afterConsume 的标准流程
- **代理模式**：AOP 实现 `@ServiceLock`、`@RepeatExecuteLimit`
- **工厂模式**：锁工厂、布隆过滤器工厂
- **观察者模式**：RabbitMQ 的 SuccessCallback / FailureCallback
- **命令模式**：延迟队列的 `ConsumerTask` 接口

---

> 本手册覆盖 damai-pro 项目的选座抢票、异步一致性、分片扩容、Sentinel 限流、验证码等核心模块，并延伸至 Seata、ShardingSphere、Redis Lua、RabbitMQ 等技术栈的深度八股文。建议按类别分批次准备，面试时先讲简历亮点再引导面试官追问。