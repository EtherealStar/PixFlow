# 对接阿里云市场异步去背景接口（submit/query）到 `infra/thirdparty`

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`、`AGENTS.md`、`docs/design-docs/index.md`、`docs/design-docs/design.md`、`docs/design-docs/infra/thirdparty.md`、`docs/design-docs/module/dag.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

## Purpose / Big Picture

这项工作要解决的问题很具体：当前 PixFlow 的去背景第三方能力已经有同步 HTTP provider 和“提交后轮询”的异步 provider，但它假设的协议形状与阿里云市场这组接口不一致。完成这项工作后，开发者可以在 `pixflow.thirdparty` 配置里把背景去除 provider 切到阿里云市场版，由 `module/dag` 继续通过统一的 `BackgroundRemovalClient.remove(...)` 调用，不需要在上层知道“先 submit、再 query”的细节。

用户可观察到的结果是：启动应用后，`remove_bg` 节点仍然只发起一次统一的背景去除调用，但底层实际会按阿里云市场协议执行 `POST /api/v1/bg-remove/submit`、拿到 `result_key`、再重复执行 `POST /api/v1/bg-remove/query` 直到拿到终态结果。若接口返回失败、超时、限流或鉴权问题，仍会被归一化成 `PixFlowException`，供上层按现有隔离策略处理。

## Progress

- [x] (2026-07-01 18:25+08:00) 阅读 `PLANS.md`、当前活动 exec plans、`docs/design-docs/infra/thirdparty.md`，确认本计划应作为独立小型 exec plan 记录阿里云市场接口接入方案。
- [x] (2026-07-01 18:25+08:00) 记录用户提供的阿里云市场 submit/query Java 示例，并对照现有 `AsyncPollingBackgroundRemovalProvider`、`ConfigurableHttpBackgroundRemovalProvider` 的协议假设，确认仅改 YAML 不能完成接入。
- [x] (2026-07-01 17:38+08:00) 新增 `AliyunMarketBackgroundRemovalProvider`，并在 `ThirdPartyAutoConfiguration` 中注册 `type=aliyun-market-bgrem` / `aliyun-market`。
- [x] (2026-07-01 17:38+08:00) 补齐 `model-type` 配置项、`application-dev.yml` 示例和 MockWebServer 单测，验证 submit/query POST JSON、动态 `X-Ca-Nonce`、`Authorization`、轮询处理中态和结果 URL 映射。

## Surprises & Discoveries

- Observation: 现有异步 provider 不是“完全配置驱动”的 submit/query 适配器，它把 submit/poll 的 HTTP method、参数承载位置和返回字段名都写死在 Java 实现里了。
  Evidence: `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/bgremoval/provider/async/AsyncPollingBackgroundRemovalProvider.java` 中 `submit()` 固定 `POST` 且默认读取 `jobId/id`，`poll()` 固定 `GET` 且通过 `statusPath` 的 `{jobId}` 占位符拼 URL。

- Observation: 阿里云市场接口要求每次请求都带不同的 `X-Ca-Nonce`，这不是静态配置值，必须在运行时生成。
  Evidence: 用户给出的 Java 示例在 submit 和 query 两个请求里都显式执行 `headers.put("X-Ca-Nonce", UUID.randomUUID().toString())`。

- Observation: 用户给出的 query 接口是 `POST /api/v1/bg-remove/query`，并把 `result_key` 放在 JSON body 内；这与当前 async provider 的“GET + path variable jobId”模型直接冲突。
  Evidence: 用户示例中的 `path = "/api/v1/bg-remove/query"`、`method = "POST"`、`bodys = "{\"data\":{\"result_key\":\"...\"}}"`。

## Decision Log

- Decision: 本计划采用“新增阿里云市场专用 provider”的方式，而不是把现有 `AsyncPollingBackgroundRemovalProvider` 改成高度通用的 submit/query DSL。
  Rationale: 当前阿里云市场协议至少包含动态 nonce、`APPCODE` 头、submit/query 都是 POST、嵌套 JSON 请求体、`result_key` 任务键等特征。若强行继续泛化现有 async provider，会把抽象变复杂、测试面变宽，并且短期只服务一个协议。新增专用 provider 更符合“供应商适配器隔离在 `infra/thirdparty` 内部”的设计边界。
  Date/Author: 2026-07-01 / Codex

- Decision: 上层统一接口 `BackgroundRemovalClient.remove(...)` 保持同步，不把 submit/query 两阶段语义泄漏到 `module/dag`。
  Rationale: `module/dag` 已将 `remove_bg` 视为确定性单元执行器中的一步；让上层感知异步任务键会破坏现有接口稳定性，也会把第三方协议细节扩散到业务模块。
  Date/Author: 2026-07-01 / Codex

- Decision: `Authorization: APPCODE <code>` 可以继续走现有 header 类鉴权配置，但 `X-Ca-Nonce` 必须由 provider 在每次请求时动态写入。
  Rationale: `Authorization` 值是静态凭证拼接，配置即可表达；nonce 是一次一值，无法放在 YAML 常量里。
  Date/Author: 2026-07-01 / Codex

## Outcomes & Retrospective

已完成阿里云市场专用 provider 接入。实现保持上层 `BackgroundRemovalClient.remove(...)` 同步接口不变，新增 provider 内部负责 `submit -> query -> query ... -> success/fail` 的协议细节；`X-Ca-Nonce` 在每次 submit/query 请求前动态生成，`Authorization: APPCODE ...` 继续复用现有 `auth.type=header` 配置。

本次实现没有把现有 `AsyncPollingBackgroundRemovalProvider` 泛化成 submit/query DSL，只为阿里云市场新增隔离适配器。新增测试使用 MockWebServer 验证最小端到端调用序列，不依赖真实外网或真实阿里云市场凭证。

## Context and Orientation

本仓库里“去背景第三方接口”的代码位于 `pixflow-infra-thirdparty` 模块。它向上暴露的统一接口是 `BackgroundRemovalClient`，调用方主要是 `module/dag` 的 `remove_bg` 节点执行链。这里的“provider”指某一家具体外部服务的适配器实现，例如 remove.bg、阿里云或其他 HTTP 服务。这里的“异步 provider”不是指 Java 线程异步，而是“调用方表面上只有一次同步方法调用，但 provider 内部会先提交任务，再轮询任务状态，直到得到最终图片结果或失败结果”。

当前与本任务直接相关的文件如下：

- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/bgremoval/BackgroundRemovalClient.java`
  统一背景去除能力接口。
- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/bgremoval/provider/async/AsyncPollingBackgroundRemovalProvider.java`
  当前“提交后轮询”的通用异步 provider，实现里已写死 submit/poll 的若干协议假设。
- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/bgremoval/provider/configurable/ConfigurableHttpBackgroundRemovalProvider.java`
  当前“配置化 HTTP provider”，只覆盖简单的 multipart、json-base64、json-url 三种请求体投影。
- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/http/DefaultThirdPartyAuthStrategy.java`
  当前统一鉴权头写入逻辑。
- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/config/ThirdPartyProperties.java`
  当前 `pixflow.thirdparty` 配置结构。
- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/config/ThirdPartyAutoConfiguration.java`
  provider bean 注册入口。
- `docs/design-docs/infra/thirdparty.md`
  当前第三方模块设计权威文档。

用户提供的目标协议示例如下。本计划把它们当成待兼容的“事实接口”，后续实现必须以它们为准，而不是反过来要求用户改接口。

任务提交接口示例：

    host = "https://bgrem.market.alicloudapi.com"
    path = "/api/v1/bg-remove/submit"
    method = "POST"
    headers:
      Authorization: APPCODE <appcode>
      Content-Type: application/json; charset=UTF-8
      X-Ca-Nonce: <每次不同的随机 UUID>
    body:
      {
        "data": {
          "task_list": [
            {
              "model_type": "general" | "human",
              "image": "https://img.alicdn.com/abc.png"
            }
          ]
        }
      }

任务查询接口示例：

    host = "https://bgrem.market.alicloudapi.com"
    path = "/api/v1/bg-remove/query"
    method = "POST"
    headers:
      Authorization: APPCODE <appcode>
      Content-Type: application/json; charset=UTF-8
      X-Ca-Nonce: <每次不同的随机 UUID>
    body:
      {
        "data": {
          "result_key": "<submit 返回的任务键>"
        }
      }

这里有两个必须明确的事实。第一，现有 provider 抽象默认“submit 返回 jobId、poll 用 GET 访问 `/status/{jobId}`”，而用户给的协议是“submit 返回 result_key、query 用 POST + JSON body”。第二，用户给的 submit 请求使用图片 URL 而不是原始 bytes，因此 provider 需要决定：是继续要求调用方传 `sourceUri`，还是在 provider 内部把 bytes 先转成可访问 URL。本计划选择第一种，即继续沿用 thirdparty 的“存储无感”原则，让调用方在需要 URL provider 时传入可访问的 HTTP/HTTPS `sourceUri`。

## Plan of Work

第一步先补齐设计和测试目标，而不是直接改已有通用 provider。新增一个阿里云市场专用 provider，例如 `AliyunMarketBackgroundRemovalProvider`，放在 `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/bgremoval/provider/aliyunmarket/` 下。这个类继续实现现有的 `BackgroundRemovalProvider`，因此对 `RoutingBackgroundRemovalClient` 和上游 `module/dag` 来说仍然只是一个新的 provider 实现。

这个 provider 的 `remove(...)` 方法内部仍走当前 `ThirdPartyCallTemplate`，继续复用信号量、超时、重试、熔断和统一错误映射。真正要变的是协议投影：先构造 submit 请求，固定 `POST` 到 `/api/v1/bg-remove/submit`；请求头里补 `Authorization: APPCODE <appcode>`、`Content-Type: application/json; charset=UTF-8` 和新的 `X-Ca-Nonce`；请求体用嵌套 JSON 承载 `data.task_list[0].image/model_type`。submit 成功后解析 `result_key`，然后在同一次 `remove(...)` 调用里循环调用 query，请求固定 `POST /api/v1/bg-remove/query`，body 为 `data.result_key`。直到 query 返回成功态结果、失败态结果或超时。

为了保持配置简单，`ThirdPartyProperties` 不必被改成高度抽象的“任意 JSON 模板引擎”。更合理的做法是在已有 `Provider` 配置基础上，为阿里云市场 provider 读取一小组专用字段：基础 `endpoint`，鉴权 `appcode`，默认 `modelType`，以及 query 轮询超时/间隔。是否通过 `auth.properties` 继续传 `appcode`，还是给 `ThirdPartyProperties.Provider` 新增更语义化的字段，由执行者在实现时二选一，但必须在实现后把设计文档补齐。当前推荐继续走 `auth.type=header` + `Authorization: APPCODE ...`，因为它对现有配置结构侵入最小。

真正需要代码承载的“新语义”有四个。第一，query 不是 GET，而是 POST JSON。第二，submit 与 query 每次都要生成不同的 `X-Ca-Nonce`。第三，submit 使用 `result_key` 而不是 `jobId/id`。第四，query 的终态解析要按阿里云市场真实返回字段来实现，而不是复用当前 async provider 的 `statusField/resultField/resultUrlField` 默认逻辑。如果真实返回结构与用户目前给出的示例不同，必须以阿里云市场正式响应为准，并在本计划的 `Surprises & Discoveries` 中补充证据。

实现完成后，应同步更新 `docs/design-docs/infra/thirdparty.md` 的“模块结构”“配置”“测试策略”三部分，明确 remove.bg、configurable-http、async-polling 之外又新增了一个阿里云市场专用 provider，避免设计文档与代码分叉。

## Concrete Steps

在仓库根目录 `D:\study\PixFlow` 执行以下步骤。下面的命令是未来实现时应运行的命令，本次只写计划，不需要现在执行。

1. 先通读与现有 provider 相关的实现和测试，确认可以复用的组件边界。

       cd D:\study\PixFlow
       rg -n "BackgroundRemovalProvider|AsyncPollingBackgroundRemovalProvider|ConfigurableHttpBackgroundRemovalProvider|ThirdPartyAuthStrategy|ThirdPartyProperties" pixflow-infra-thirdparty

   预期结果：能定位到 provider、配置、鉴权和自动装配代码，确认新 provider 的挂载点。

2. 新增阿里云市场 provider 实现和对应测试。

       cd D:\study\PixFlow
       mvn -pl pixflow-infra-thirdparty -am test

   预期结果：先看到现有测试绿；实现后新增针对 submit/query 的单元测试和 HTTP 投影测试。

3. 在 `pixflow-app/src/main/resources/application-dev.yml` 中增加一段示例配置，便于本地切 provider。

       pixflow:
         thirdparty:
           bg-removal:
             default-provider: aliyun-market
           providers:
             aliyun-market:
               type: aliyun-market-bgrem
               enabled: true
               endpoint: https://bgrem.market.alicloudapi.com
               auth:
                 type: header
                 properties:
                   header: Authorization
                   value: APPCODE ${ALIYUN_BGREM_APPCODE:}

   预期结果：配置层能明确选中阿里云市场 provider，appcode 通过环境变量注入。

4. 用 mock HTTP 服务验证 submit/query 交互，不依赖真实外网。

       cd D:\study\PixFlow
       mvn -pl pixflow-infra-thirdparty -am test

   预期结果：测试中能观测到先 `POST /submit`，再若干次 `POST /query`，并验证请求头包含 `Authorization` 与不同的 `X-Ca-Nonce`。

## Validation and Acceptance

验收标准不是“加了一个类”，而是以下行为真实成立：

当 `pixflow.thirdparty.bg-removal.default-provider=aliyun-market` 且配置了 `ALIYUN_BGREM_APPCODE` 时，`module/dag` 仍然只需调用一次 `BackgroundRemovalClient.remove(...)`，底层 provider 会先发 submit，再按 query 轮询，最后返回一张结果图或一个标准化错误。调用方不需要自己管理 `result_key`，也不需要知道 `X-Ca-Nonce`。

测试至少需要覆盖这些场景：

- submit 请求头正确：`Authorization: APPCODE ...` 存在，`X-Ca-Nonce` 存在且非空。
- query 请求头正确：同样有 `Authorization` 和新的 `X-Ca-Nonce`。
- submit 请求体正确：包含 `data.task_list[0].image` 和 `data.task_list[0].model_type`。
- submit 返回 `result_key` 后，query body 正确包含 `data.result_key`。
- query 返回处理中状态时，provider 会继续轮询而不是提前终止。
- query 返回成功状态时，provider 能正确提取结果图或结果图 URL 并转换为 `BackgroundRemovalResult`。
- query 返回失败状态时，provider 抛出标准化 `PixFlowException`。
- query 超时或 HTTP 429/5xx 时，仍走当前 thirdparty 的错误映射和重试策略。

如果接入真实阿里云市场环境，人工可见的证明应是：调用一次背景去除后，日志和测试桩显示顺序为 `submit -> query -> query ... -> success/fail`，而不是只发一个 HTTP 请求。

## Idempotence and Recovery

本计划是增量式的，适合多次重复执行。新增 provider 和测试不会破坏现有 remove.bg 路径；即使阿里云市场适配实现中途失败，也可以通过不切换 `default-provider` 保持当前系统可用。若某次实现发现 query 响应结构与本文假设不一致，优先补测试与计划文档，再修改 provider 解析逻辑，不要先改上游 `module/dag` 接口。

如果实现时误把通用 async provider 改复杂了，安全回退方式是保留新增的阿里云市场专用 provider，撤回对通用 async provider 的过度泛化改动。目标是“新增一条稳定路径”，不是“重写整套 thirdparty 抽象”。

## Artifacts and Notes

下面这段是本计划推荐保留在文档中的配置草案，后续实现应尽量与之接近：

    pixflow:
      thirdparty:
        bg-removal:
          default-provider: aliyun-market
        providers:
          aliyun-market:
            type: aliyun-market-bgrem
            enabled: true
            endpoint: https://bgrem.market.alicloudapi.com
            auth:
              type: header
              properties:
                header: Authorization
                value: APPCODE ${ALIYUN_BGREM_APPCODE:}
            polling:
              submit-path: /api/v1/bg-remove/submit
              status-path: /api/v1/bg-remove/query
              timeout: 30s
              interval: 1s

下面这段是执行者实现测试时应观察到的最小请求序列：

    POST /api/v1/bg-remove/submit
    Authorization: APPCODE ***
    X-Ca-Nonce: <uuid-1>
    body.data.task_list[0].image = "https://..."

    POST /api/v1/bg-remove/query
    Authorization: APPCODE ***
    X-Ca-Nonce: <uuid-2>
    body.data.result_key = "<submit 返回值>"

注意：上面的 `polling.submit-path/status-path` 只是为了表达接口路径，不代表现有 `AsyncPollingBackgroundRemovalProvider` 可以直接复用；当前实现仍需新增专用 provider 才能支持 query 用 POST body 的协议。

## Interfaces and Dependencies

实现完成后，以下类型和边界必须存在或继续成立：

在 `pixflow-infra-thirdparty` 中保留现有统一接口：

    public interface BackgroundRemovalClient {
        BackgroundRemovalResult remove(BackgroundRemovalRequest req);
    }

新增一个阿里云市场专用 provider，实现现有 `BackgroundRemovalProvider`：

    final class AliyunMarketBackgroundRemovalProvider implements BackgroundRemovalProvider

这个类至少需要以下职责：

- 构造 submit 请求：`POST /api/v1/bg-remove/submit`
- 构造 query 请求：`POST /api/v1/bg-remove/query`
- 每次请求生成新的 `X-Ca-Nonce`
- 从 submit 响应中读取 `result_key`
- 从 query 响应中读取任务状态和最终结果
- 复用 `ThirdPartyCallTemplate`、`RestClientThirdPartyHttpInvoker`、`ThirdPartyErrorMapper`

`ThirdPartyAutoConfiguration` 必须能够按 `type=aliyun-market-bgrem` 注册这个 provider。`pixflow-app` 只需要通过配置切 provider，不应增加新的 controller、service 或 `module/dag` 接口。上游仍通过 `BackgroundRemovalClient.remove(...)` 访问能力，这一点不能变。

## Revision Notes

2026-07-01 / Codex: 新建本计划，用于记录用户提供的阿里云市场去背景 submit/query 接口示例，并明确指出当前 `infra/thirdparty` 的通用 async/configurable provider 无法仅靠配置完成接入，后续应新增阿里云市场专用 provider 实现。该计划当前不涉及代码改动，只作为后续实现依据。

2026-07-01 / Codex: 按计划完成实现，新增 `AliyunMarketBackgroundRemovalProvider`、自动装配类型 `aliyun-market-bgrem`、`model-type` 配置、开发配置示例和 MockWebServer 单测，并同步更新 `docs/design-docs/infra/thirdparty.md`。
