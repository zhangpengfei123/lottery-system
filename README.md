# PF Market

PF Market 是一个基于 Spring Boot 和 DDD 分层思想实现的营销抽奖系统，核心围绕活动配置、抽奖策略、库存扣减、中奖记录、MQ 发奖和任务补偿等业务流程展开。

## 项目简介

本项目适合用来学习中后台营销系统的典型设计方式。代码里把业务拆成了活动、策略、奖品、任务等子域，并通过领域服务、仓储接口、基础设施实现和触发器入口进行分层组织。

主要能力包括：

- 活动装配与活动 SKU 库存预热
- 抽奖策略装配、权重规则、黑名单规则和库存规则
- 用户参与活动并生成抽奖订单
- 执行抽奖并写入中奖记录
- 基于 RabbitMQ 的发奖消息投递
- 基于任务表的消息补偿机制
- Redis 缓存与库存扣减
- MyBatis 持久化与分库分表路由配置

## 技术栈

- Java 8
- Spring Boot 2.7.12
- MyBatis
- MySQL 8
- Redis / Redisson
- RabbitMQ
- Maven 多模块
- Docker Compose
- Lombok
- Guava
- Fastjson

## 模块说明

```text
PF-market
├── big-market-app             # Spring Boot 启动模块，负责配置装配和应用启动
├── big-market-api             # 对外接口和 DTO 定义
├── big-market-domain          # 领域层，存放核心业务模型、服务和仓储接口
├── big-market-infrastructure  # 基础设施层，负责 MySQL、Redis、MQ 等实现
├── big-market-trigger         # 触发器层，包含 HTTP 接口、定时任务、MQ 监听器
├── big-market-types           # 通用类型、响应对象、异常、常量和事件基类
└── docs                       # 部署脚本、数据库脚本和项目资料
```

### 分层理解

- `big-market-trigger`：接收外部请求，比如 HTTP 接口、定时任务、MQ 消息。
- `big-market-domain`：承载核心业务规则，比如抽奖、活动参与、库存扣减和发奖。
- `big-market-infrastructure`：把领域层需要的仓储接口落地到 MySQL、Redis、RabbitMQ。
- `big-market-app`：负责启动 Spring Boot 应用，并把各模块组装起来。
- `big-market-types`：放全项目通用对象，避免每个模块重复定义响应、异常和常量。

这种结构的好处是：业务规则集中在领域层，数据库、缓存、消息队列等技术细节被隔离在基础设施层，后续维护和扩展会更清晰。

## 本地运行

### 1. 准备基础环境

先进入部署目录：

```bash
cd docs/dev-ops
```

启动 MySQL、Redis、RabbitMQ：

```bash
docker-compose -f docker-compose-environment.yml up -d
```

默认端口：

- MySQL：`13306`
- Redis：`16379`
- RabbitMQ：`5672`
- RabbitMQ 控制台：`15672`
- phpMyAdmin：`8899`

默认账号：

- MySQL：`root / 123456`
- RabbitMQ：`admin / admin`
- Redis Admin：`admin / admin`

### 2. 编译项目

回到项目根目录执行：

```bash
mvn clean package
```

### 3. 启动应用

可以直接运行启动类：

```text
big-market-app/src/main/java/cn/bugstack/Application.java
```

也可以使用 Maven：

```bash
mvn -pl big-market-app spring-boot:run
```


本地环境使用分库分表配置，主要数据库包括：

- `big_market`
- `big_market_01`
- `big_market_02`

## 作者

zpf666