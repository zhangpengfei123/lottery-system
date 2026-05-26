# big-market Project Architecture (DDD)
#
# NOTE: This doc is written in Markdown and aims to explain:
# - overall module structure
# - DDD layering and dependency direction
# - how the main business flows connect (HTTP -> Domain -> Infra)
# - a concrete end-to-end walkthrough you can run locally
#
# ------------------------------------------------------------

# big-market 项目架构梳理（DDD 分层）

> 这份文档基于当前仓库代码结构与配置文件梳理而来，目标是让你能从“模块 -> 分层 -> 关键流程 -> 可跑起来的例子”完整理解项目。

---

## 1. 项目是什么

`big-market` 是一个以“营销抽奖/活动”为核心的后端工程，采用 **DDD（领域驱动设计）分层** 组织代码：

- **活动（activity）**：活动、SKU 库存、参与额度、下单/参与等。
- **抽奖策略（strategy）**：奖品概率、规则（黑名单/权重/次数锁/库存/兜底）、抽奖流程编排。
- **发奖（award）**：用户中奖记录、发奖消息（MQ）投递，以及投递失败的补偿（任务表）。
- **任务（task）**：扫描任务表，补偿发送 MQ。

整体是一个典型的“HTTP 接口 + Redis 缓存/扣减 + MySQL 持久化 + RabbitMQ 异步消息 + 定时任务补偿”的组合。

---

## 2. 技术栈与关键依赖

- Java：`1.8`（见根 `pom.xml` 的 `java.version`）
- Spring Boot：`2.7.12`
- MyBatis：`mybatis-spring-boot-starter`
- Redis：Redisson（见 `big-market-app/src/main/java/cn/bugstack/config/RedisClientConfig.java`）
- RabbitMQ：Spring AMQP（消息发布/消费）
- 分库分表：`cn.bugstack.middleware:db-router-spring-boot-starter`
- 其他：`fastjson/fastjson2`、`guava`、`commons-lang3`

---

## 3. 一眼看懂的模块结构（Maven Modules）

根聚合 `pom.xml` 定义的模块（`<modules>`）：

- `big-market-app`：Spring Boot 启动模块（Application + 配置 + resources）
- `big-market-trigger`：对外入口适配层（HTTP Controller / 定时任务 / MQ Listener）
- `big-market-domain`：领域层（业务模型、领域服务、规则引擎、仓储接口）
- `big-market-infrastructure`：基础设施层（DAO/MyBatis、Redis 封装、仓储实现、MQ Publisher）
- `big-market-types`：通用类型（常量、统一返回、异常、事件基类）
- `big-market-api`：对外 API 接口与 DTO（`IRaffleService` + DTO）

仓库里还存在 `big-market-querys/`，但当前只看到 `target/`，不在 Maven modules 中，可视为历史遗留或未纳入构建的目录。

---

## 4. DDD 分层与依赖方向（非常关键）

DDD 推荐依赖方向是“外层依赖内层”，项目基本符合这个方向：

```text
┌──────────────────────────────┐
│ big-market-app                │  启动/装配（Spring Boot）
└───────────────┬──────────────┘
                │ 依赖
┌───────────────▼──────────────┐
│ big-market-trigger            │  入口层（HTTP/Job/Listener）
└───────────────┬──────────────┘
                │ 依赖
┌───────────────▼──────────────┐
│ big-market-domain             │  领域层（核心业务）
└───────────────┬──────────────┘
                │ 通过接口反转依赖
┌───────────────▼──────────────┐
│ big-market-infrastructure     │  基础设施（DB/Redis/MQ）
└──────────────────────────────┘

┌──────────────────────────────┐
│ big-market-types              │  通用类型（被各层引用）
└──────────────────────────────┘
```

理解这个结构后，你会发现：

- **domain 只定义“要做什么”（接口/模型/规则）**，不关心“怎么存/怎么发消息/怎么缓存”。
- **infrastructure 实现 domain 的仓储接口**，把 DB/Redis/MQ 细节“藏起来”。
- **trigger 负责把外部请求转换为领域对象，然后调用领域服务**。

---

## 5. 各模块内部结构（按目录讲）

### 5.1 `big-market-app`（启动与装配）

入口：

- `big-market-app/src/main/java/cn/bugstack/Application.java`：`@SpringBootApplication` + `@EnableScheduling`

配置：

- 线程池：`big-market-app/src/main/java/cn/bugstack/config/ThreadPoolConfig.java`
- Redis（Redisson）：`big-market-app/src/main/java/cn/bugstack/config/RedisClientConfig.java`
- 配置绑定：`ThreadPoolConfigProperties`、`RedisClientConfigProperties`

资源文件：

- 环境配置：`big-market-app/src/main/resources/application-*.yml`
  - `application.yml` 默认启用 `dev`：`spring.profiles.active: dev`
  - `application-dev.yml`：本地开发（含多数据源路由、Redis、RabbitMQ）
  - `application-prod.yml`：生产/单库版本示例
- MyBatis 映射：`big-market-app/src/main/resources/mybatis/mapper/*.xml`

### 5.2 `big-market-trigger`（入口适配层）

HTTP：

- `big-market-trigger/src/main/java/cn/bugstack/trigger/http/RaffleController.java`
  - `GET  /api/v1/raffle/strategy_armory?strategyId=...`：策略装配/预热
  - `POST /api/v1/raffle/query_raffle_award_list`：奖品列表
  - `POST /api/v1/raffle/random_raffle`：抽奖

定时任务（`@Scheduled`）：

- `UpdateAwardStockJob`：消费“奖品库存扣减队列”，把 Redis 扣减结果落库
- `UpdateActivitySkuStockJob`：消费“活动 SKU 库存扣减队列”，把 Redis 扣减结果落库
- `SendMessageTaskJob`：扫描 task 表，补偿发送 MQ（分库扫描 + 线程池并发）

MQ Listener：

- `ActivitySkuStockZeroCustomer`：监听 `activity_sku_stock_zero`，库存为 0 时清库/清队列
- `SendAwardCustomer`：监听 `send_award`，模拟“发奖消息消费”

### 5.3 `big-market-domain`（核心业务层）

按业务子域拆分：

- `domain/activity`：活动 + SKU 库存 + 参与额度 + 订单
- `domain/strategy`：抽奖策略（责任链 + 决策树）
- `domain/award`：中奖记录与发奖消息（Outbox 风格任务表）
- `domain/task`：消息任务补偿（与 `SendMessageTaskJob` 对应）

这里最值得你重点读的文件（理解项目“骨架”）：

- 抽奖主流程：`domain/strategy/service/AbstractRaffleStrategy.java`
- 策略预热：`domain/strategy/service/armory/StrategyArmoryDispatch.java`
- 责任链工厂：`domain/strategy/service/rule/chain/factory/DefaultChainFactory.java`
- 决策树引擎：`domain/strategy/service/rule/tree/factory/engine/impl/DecisionTreeEngine.java`
- 库存扣减节点：`domain/strategy/service/rule/tree/impl/RuleStockLogicTreeNode.java`

### 5.4 `big-market-infrastructure`（基础设施实现）

持久化（MyBatis）：

- DAO：`big-market-infrastructure/src/main/java/cn/bugstack/infrastructure/persistent/dao/*Dao.java`
- PO：`big-market-infrastructure/src/main/java/cn/bugstack/infrastructure/persistent/po/*.java`
- MyBatis XML：`big-market-app/src/main/resources/mybatis/mapper/*.xml`

仓储实现（domain 的 repository interface 在这里落地）：

- 活动仓储：`.../persistent/repository/ActivityRepository.java`
- 策略仓储：`.../persistent/repository/StrategyRepository.java`
- 发奖仓储：`.../persistent/repository/AwardRepository.java`
- 任务仓储：`.../persistent/repository/TaskRepository.java`

Redis 封装：

- `.../persistent/redis/IRedisService.java` + `RedissonService.java`

MQ 发布：

- `.../event/EventPublisher.java`：对 `RabbitTemplate` 的薄封装

### 5.5 `big-market-types`（通用类型）

统一响应/异常/常量：

- Redis key：`big-market-types/src/main/java/cn/bugstack/types/common/Constants.java`
- 统一错误码：`big-market-types/src/main/java/cn/bugstack/types/enums/ResponseCode.java`
- 统一响应：`big-market-types/src/main/java/cn/bugstack/types/model/Response.java`
- 业务异常：`big-market-types/src/main/java/cn/bugstack/types/exception/AppException.java`
- 事件基类：`big-market-types/src/main/java/cn/bugstack/types/event/BaseEvent.java`

### 5.6 `big-market-api`（对外 API/DTO）

- 服务接口：`big-market-api/src/main/java/cn/bugstack/trigger/api/IRaffleService.java`
- DTO：`big-market-api/src/main/java/cn/bugstack/trigger/api/dto/*DTO.java`

在 `big-market-trigger` 里由 `RaffleController` 实现该接口，形成“接口模块（api）”与“实现模块（trigger）”的分离。

---

## 6. 关键机制：这些模块是如何产生联系的

下面用“你看得见的调用关系”来解释模块如何串起来。

### 6.1 抽奖策略：装配（预热） -> 抽奖（责任链） -> 过滤（决策树）

#### 6.1.1 策略装配（预热）

入口：

- `RaffleController.strategyArmory()` -> `IStrategyArmory.assembleLotteryStrategy(strategyId)`
- 实现：`StrategyArmoryDispatch.assembleLotteryStrategy(strategyId)`

它做的事（非常重要）：

1. 从 DB 查 `strategy_award` 等配置（通过 `IStrategyRepository`）
2. 把“奖品列表/库存/概率查找表/权重概率表”等数据预热到 Redis
3. 后续抽奖时就可以 **只从 Redis 取随机结果**，避免每次都查 DB

#### 6.1.2 抽奖主流程（标准流程定义在抽象类里）

- `IRaffleStrategy.performRaffle(RaffleFactorEntity)`
- 实现流程在 `AbstractRaffleStrategy.performRaffle()`：把抽奖拆成 3 段

1. 参数校验
2. **责任链**：得到“初步 awardId”（黑名单/权重/默认概率）
3. **决策树**：对 awardId 做“次数锁/库存扣减/兜底”等过滤，得到最终 awardId + ruleValue

最终查询奖品信息封装为 `RaffleAwardEntity` 返回给 trigger。

#### 6.1.3 责任链：把“前置规则”拆成可插拔组件

工厂：

- `DefaultChainFactory.openLogicChain(strategyId)`
  - 从 `StrategyEntity.ruleModels()` 读到规则顺序（例如：`rule_blacklist`、`rule_weight`）
  - 用 Spring `ApplicationContext.getBean(ruleModel, ILogicChain.class)` 取到对应实现
  - 组装成链，最后挂上 `rule_default`

链节点示例：

- 黑名单：`BackListLogicChain`（命中直接返回固定 awardId）
- 权重：`RuleWeightLogicChain`（按积分命中某个“权重范围”，走专用概率表）
- 默认：`DefaultLogicChain`（走默认概率表）

#### 6.1.4 决策树：把“抽中后规则”做成可配置树

决策树引擎：

- `DecisionTreeEngine.process(userId, strategyId, awardId)`

树数据来自 DB 表（并有缓存）：

- `rule_tree`、`rule_tree_node`、`rule_tree_node_line`

节点实现示例（用 `@Component(\"rule_xxx\")` 注册）：

- `RuleLockLogicTreeNode`：次数锁（ruleValue 为阈值）
- `RuleStockLogicTreeNode`：库存扣减（扣 Redis 原子库存）
- `RuleLuckAwardLogicTreeNode`：兜底奖品（ruleValue 配置 “awardId:awardRuleValue”）

### 6.2 库存扣减：Redis 先扣 -> 延迟队列 -> 定时任务落库

你会在两个地方看到同样的模式：

- 策略奖品库存：`StrategyRepository.awardStockConsumeSendQueue()` + `UpdateAwardStockJob`
- 活动 SKU 库存：`ActivityRepository.activitySkuStockConsumeSendQueue()` + `UpdateActivitySkuStockJob`

为什么这么做：

- 抽奖/下单是高频写操作；直接每次都更新 MySQL 容易把 DB 打满
- 先在 Redis 扣减，保证并发正确性；再异步合并更新 MySQL（最终一致）

并且有“兜底防超卖锁”：

- 扣减后用 `setNx(lockKey)` 把每一个库存号位都锁住，避免人工补库存/异常情况下出现超卖

活动 SKU 还有一个特殊逻辑：

- 当库存扣减到 `0` 时（`surplus == 0`），`ActivityRepository.subtractionActivitySkuStock` 会发 MQ：`activity_sku_stock_zero`
- `ActivitySkuStockZeroCustomer` 监听到后，清理数据库库存并清空队列

### 6.3 发奖与 Outbox：同事务写记录+任务表，事务外发 MQ，失败可补偿

这块在 `award` 子域里最典型：

1. 领域服务 `AwardService.saveUserAwardRecord()` 构建：
   - `UserAwardRecordEntity`（中奖记录）
   - `TaskEntity`（待发送消息任务）
   - 聚合 `UserAwardRecordAggregate`
2. `AwardRepository.saveUserAwardRecord()` 在一个事务里：
   - 插入 `user_award_record`
   - 插入 `task`
3. 事务外：
   - 发送 MQ（`EventPublisher.publish`）
   - 更新 task 状态为“已发送/发送失败”
4. 如果发送失败：
   - `SendMessageTaskJob` 会定时扫描 task 表补偿发送

这是一种非常常见、很实用的“可靠消息最终一致”实现方式（你可以把它理解成简化版 Outbox Pattern）。

---

## 7. 本地运行（建议按文档里的 docker-compose）

### 7.1 启动依赖：MySQL + Redis + RabbitMQ

项目自带了环境编排：

- `docs/dev-ops/docker-compose-environment.yml`

在该目录下执行：

```bash
docker-compose -f docker-compose-environment.yml up -d
```

端口（与 `application-dev.yml` 对应）：

- MySQL：`13306 -> 3306`（容器内 3306）
- Redis：`16379 -> 6379`
- RabbitMQ：`5672`、管理台 `15672`
- phpMyAdmin：`8899`
- Redis Commander：`8081`（admin/admin）

数据库初始化脚本：

- `docs/dev-ops/mysql/sql/big_market.sql`
- `docs/dev-ops/mysql/sql/big_market_01.sql`
- `docs/dev-ops/mysql/sql/big_market_02.sql`

docker compose 会把 `docs/dev-ops/mysql/sql` 挂载到 `/docker-entrypoint-initdb.d`，首次启动会自动执行。

### 7.2 启动应用

默认 profile 是 `dev`（见 `big-market-app/src/main/resources/application.yml`）。

你可以直接运行：

- `big-market-app/src/main/java/cn/bugstack/Application.java`

启动后默认端口：

- `8091`（见 `application-dev.yml`）

---

## 8. 端到端例子：走完整抽奖链路（两种方式）

### 方式 A：通过 HTTP 接口

1) 预热策略（把概率表/库存等装配进 Redis）：

```bash
curl "http://localhost:8091/api/v1/raffle/strategy_armory?strategyId=100006"
```

2) 查询奖品列表：

```bash
curl -X POST "http://localhost:8091/api/v1/raffle/query_raffle_award_list" \
  -H "Content-Type: application/json" \
  -d "{\"strategyId\":100006}"
```

3) 发起抽奖：

```bash
curl -X POST "http://localhost:8091/api/v1/raffle/random_raffle" \
  -H "Content-Type: application/json" \
  -d "{\"strategyId\":100006}"
```

你会看到：

- `RaffleController`（trigger）负责组装 `RaffleFactorEntity`
- `AbstractRaffleStrategy`（domain）跑完整责任链 + 决策树
- `StrategyRepository`（infrastructure）读 DB/Redis、扣库存、写延迟队列
- `UpdateAwardStockJob`（trigger）异步消费队列并落库

### 方式 B：通过测试用例（更适合看代码调用栈）

抽奖策略测试：

- `big-market-app/src/test/java/cn/bugstack/test/domain/strategy/RaffleStrategyTest.java`
  - `setUp()` 里会执行 `strategyArmory.assembleLotteryStrategy(...)`
  - `test_performRaffle()` 会循环调用 `raffleStrategy.performRaffle(...)`
  - 你可以在这里用 `ReflectionTestUtils` 造一些“积分/次数”数据触发规则分支

活动参与下单测试：

- `big-market-app/src/test/java/cn/bugstack/test/domain/activity/RaffleActivityPartakeServiceTest.java`

发奖 + MQ + task 补偿测试：

- `big-market-app/src/test/java/cn/bugstack/test/domain/award/AwardServiceTest.java`

---

## 9. 附录：你可以重点读的 10 个文件（学习路线）

1. `big-market-app/src/main/java/cn/bugstack/Application.java`
2. `big-market-trigger/src/main/java/cn/bugstack/trigger/http/RaffleController.java`
3. `big-market-domain/src/main/java/cn/bugstack/domain/strategy/service/AbstractRaffleStrategy.java`
4. `big-market-domain/src/main/java/cn/bugstack/domain/strategy/service/armory/StrategyArmoryDispatch.java`
5. `big-market-domain/src/main/java/cn/bugstack/domain/strategy/service/rule/chain/factory/DefaultChainFactory.java`
6. `big-market-domain/src/main/java/cn/bugstack/domain/strategy/service/rule/tree/factory/engine/impl/DecisionTreeEngine.java`
7. `big-market-domain/src/main/java/cn/bugstack/domain/strategy/service/rule/tree/impl/RuleStockLogicTreeNode.java`
8. `big-market-infrastructure/src/main/java/cn/bugstack/infrastructure/persistent/repository/StrategyRepository.java`
9. `big-market-infrastructure/src/main/java/cn/bugstack/infrastructure/persistent/repository/AwardRepository.java`
10. `big-market-trigger/src/main/java/cn/bugstack/trigger/job/SendMessageTaskJob.java`

