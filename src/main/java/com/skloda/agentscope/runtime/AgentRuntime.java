package com.skloda.agentscope.runtime;

import com.skloda.agentscope.hook.ObservabilityHook;
import com.skloda.agentscope.middleware.ApprovalMiddleware;
import com.skloda.agentscope.service.ApprovalService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runtime container for a single Agent interaction session.
 * Uses agent.streamEvents() for automatic lifecycle events (AgentScope 2.0)
 * and EventSink for manual multi-agent events.
 */
public class AgentRuntime implements StreamingAgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final AgentEventMapper eventMapper = new AgentEventMapper();

    @Getter
    private final ReActAgent agent;
    @Getter
    private final ObservabilityHook hook;
    private final ApprovalMiddleware approvalMiddleware;
    private final ApprovalService approvalService;
    private final String agentId;
    private final String sessionId;
    private final Runnable onClose;

    public AgentRuntime(ReActAgent agent, ObservabilityHook hook) {
        this(agent, hook, null, null, null, null, null);
    }

    public AgentRuntime(ReActAgent agent, ObservabilityHook hook, ApprovalMiddleware approvalMiddleware,
                        ApprovalService approvalService, String agentId) {
        this(agent, hook, approvalMiddleware, approvalService, agentId, null, null);
    }

    public AgentRuntime(ReActAgent agent, ObservabilityHook hook, ApprovalMiddleware approvalMiddleware,
                        ApprovalService approvalService, String agentId, String sessionId,
                        Runnable onClose) {
        this.agent = agent;
        this.hook = hook;
        this.approvalMiddleware = approvalMiddleware;
        this.approvalService = approvalService;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.onClose = onClose;
    }

    @Override
    public Flux<Map<String, Object>> stream(Msg userMsg) {
        return stream(userMsg, false);
    }

    /**
     * 把一次 Agent 执行转换成前端可消费的 SSE 事件流。
     *
     * <p>返回的每个 Map 都是一个 SSE 数据对象，通常至少包含 {@code type} 字段。
     * 这个方法合并了两类事件：
     * <ol>
     *   <li>Agent 自动产生的事件：模型调用、思考、文本增量、工具调用、最终结果等；
     *   <li>多 Agent 编排代码手动产生的事件：pipeline、routing、handoff 等。</li>
     * </ol>
     *
     * <p>{@code AgentEvent} 并不是在这里“生成类型”的。{@code agent.streamEvents(userMsg)}
     * 会启动 AgentScope 的 ReActAgent 执行流程；当执行到某个阶段时，框架创建对应的
     * {@link AgentEvent} 子类实例并写入其内部 FluxSink。例如：
     *
     * <pre>{@code
     * AgentStartEvent           -> AgentEventType.AGENT_START
     * ModelCallStartEvent       -> AgentEventType.MODEL_CALL_START
     * TextBlockDeltaEvent       -> AgentEventType.TEXT_BLOCK_DELTA
     * ToolCallStartEvent        -> AgentEventType.TOOL_CALL_START
     * AgentResultEvent          -> AgentEventType.AGENT_RESULT
     * AgentEndEvent             -> AgentEventType.AGENT_END
     * }</pre>
     *
     * <p>这些子类分别重写 {@link AgentEvent#getType()}，所以 {@code event.getType()} 返回的是
     * 该事件对应的 {@code AgentEventType} 枚举。{@link AgentEventMapper} 再按这个枚举生成前端
     * 使用的 Map（例如 {@code TEXT_BLOCK_DELTA} 会变成 {@code {"type":"text", ...}}）。
     *
     * @param userMsg          用户消息；审批通过后恢复执行时可以为 {@code null}
     * @param isApprovalResume {@code false} 表示普通请求，{@code true} 表示 HITL 审批后的续跑
     * @return 合并后的前端事件流；只有被订阅后，Reactor 才会实际拉取 Agent 事件
     */
    public Flux<Map<String, Object>> stream(Msg userMsg, boolean isApprovalResume) {
        log.debug("Starting stream for agent: {} (resume={})", agent.getName(), isApprovalResume);

        // 关键步骤 1：普通新请求开始时，先清理上一次请求中断后遗留的“孤立工具调用”。
        //
        // 会话模式下，AgentStateStore 会跨请求保存上下文。如果上次请求在模型已经
        // 生成 ToolUseBlock、但工具还没有执行完时被取消或超时，上下文中就会留下
        // 一个没有对应 ToolResultBlock 的工具调用。下一次请求时，AgentScope 的
        // pending-tool-recovery 会自动补一个错误结果，污染后续对话，因此这里主动
        // 删除这类残留的 assistant 消息。
        //
        // 审批续跑不能清理，因为待执行的工具调用正是恢复流程要继续处理的内容。
        if (!isApprovalResume) {
            clearStalePendingToolCalls();
        }

        // 关键步骤 2：取得“手动多 Agent 事件”流。
        //
        // 这条流来自 Sinks.Many<Map<String,Object>>，事件由编排代码通过
        // ObservabilityHook/EventSink 主动 emit，例如 pipeline_start、routing_decision、
        // handoff_start。它与下面 AgentScope 自动产生的 AgentEvent 是完全不同的来源。
        Flux<Map<String, Object>> sinkEvents = hook.getEventSink().asFlux();

        Flux<Map<String, Object>> agentEvents;
        if (isApprovalResume) {
            // 审批后的续跑：重新调用 Agent，以取得审批后执行阶段产生的完整事件流。
            agentEvents = createApprovalResumeStream(userMsg);
        } else {
            // 普通请求：AgentScope 自动产生事件。
            //
            // streamEvents 返回 Flux<AgentEvent>。上游每一个元素都是框架创建的某个具体
            // AgentEvent 子类实例，例如 AgentStartEvent、ModelCallStartEvent、
            // TextBlockDeltaEvent、ToolCallStartEvent、ToolResultEndEvent 等。事件类型
            // 不是这里手工判断出来的：框架在 ReAct 循环执行到相应阶段时创建对应子类，
            // 子类的 getType() 再返回对应 AgentEventType。
            //
            // handle 是“一对零或一”的转换：
            //   1. event 是上游 Flux 中的一条 AgentEvent；
            //   2. mapAgentEvent(event) 把它转换成前端需要的 Map；
            //   3. 只有转换结果非空时才调用 sink.next(map)，否则直接跳过。
            //
            // 这里不用 map，是因为 AgentEventMapper.apply() 会对块边界等无需展示的事件
            // 返回 null；Reactor 的 map 不允许返回 null，而 handle 可以直接不向下游发元素。
            agentEvents = agent.streamEvents(userMsg)
                    .handle((event, sink) -> {
                        // mapAgentEvent 内部先调用 AgentEventMapper.apply(event)。
                        // Mapper 用 switch(event.getType()) 生成 type 字段，并对 tool_start
                        // 额外补充 MCP 来源信息。
                        Map<String, Object> map = mapAgentEvent(event);

                        // null/空 Map 表示该事件仅供框架内部协调，前端不需要展示。
                        // 注意：这个 sink 是 handle 提供的下游 SynchronousSink，
                        // 不是上面手动多 Agent 事件使用的 EventSink。
                        if (map != null && !map.isEmpty()) {
                            sink.next(map);
                        }
                    });
        }

        // Complete the EventSink when the agent event stream terminates so that
        // Flux.merge(sinkEvents, agentEvents) can complete and the trailing 'done'
        // (or 'pending_approval') event from concatWith is emitted.
        //
        // Previously the sink was only completed in the outer flux's doOnComplete /
        // doOnError — a circular dependency, since the outer flux's completion
        // depends on the merge completing, which in turn depends on the sink
        // completing (Sinks.Many never auto-completes). This left the stream
        // hanging after the agent finished, so the frontend never received the
        // 'done' SSE event, isStreaming stayed true, and the input box remained
        // permanently disabled ("只能聊一次"). Moving the sink completion to
        // agentEvents.doFinally breaks the cycle: agent done -> sink done ->
        // merge done -> 'done' emitted.
        //
        // 关键步骤 3：Agent 事件结束时，同时结束手动事件流。
        //
        // Flux.merge() 必须等待两条上游流都完成，自身才会完成。Agent 事件流会自然
        // complete/error，但 Sinks.Many 不会自动结束，所以这里用 doFinally 结束
        // EventSink。否则 merge 永远不完成，后面的 done/pending_approval 也发不出去。
        //
        // 流程是：agentEvents 结束 -> EventSink 结束 -> merge 完成
        //       -> concatWith 的收尾事件被发送。
        Flux<Map<String, Object>> agentEventsWithSinkCompletion = agentEvents
                .doFinally(signal -> hook.getEventSink().complete());

        // 关键步骤 4：并行合并两条事件流。Agent 生命周期事件和手动编排事件哪个先发生
        // 就先向下游发送，不做固定顺序等待。
        return Flux.merge(sinkEvents, agentEventsWithSinkCompletion)
                .concatWith(Mono.fromCallable(() -> {
                    // 所有 Agent 事件结束后，只追加一个收尾事件：
                    // - 如果中间件拦截到了待审批工具，返回 pending_approval；
                    // - 否则返回 done，通知前端本轮流结束。
                    if (approvalMiddleware != null && approvalMiddleware.isApprovalTriggered()) {
                        return handleApprovalCompletion();
                    }
                    return Map.of("type", "done");
                }))
                // 无论正常结束、异常还是客户端取消，都释放 Runtime 相关资源。
                .doOnCancel(this::close)
                .doOnComplete(this::close)
                .doOnError(e -> {
                    log.error("Stream error for agent: {}", agent.getName(), e);
                    this.close();
                });
    }

    /**
     * Approval resume path: re-invokes the agent via {@code streamEvents()} so that the
     * post-approval execution emits the same typed lifecycle events (thinking, tool_start,
     * tool_end, text, …) as the original stream. Previously this used {@code agent.call()},
     * which returned only the final {@link Msg} and dropped all intermediate events — leaving
     * the frontend with bare text after an approval.
     *
     * <p>If a non-null {@code userMsg} is supplied (the rejection-message path), it is fed to
     * the agent; otherwise the agent resumes its pending tool execution with an empty message
     * list (the framework's pending-tool-recovery path, enabled via
     * {@code enablePendingToolRecovery(true)} on the builder).
     */
    private Flux<Map<String, Object>> createApprovalResumeStream(Msg userMsg) {
        Flux<AgentEvent> resumeEvents = userMsg != null
                ? agent.streamEvents(userMsg)
                : agent.streamEvents(List.of());
        // Use handle() instead of map()+filter(): map() throws on null return,
        // but AgentEventMapper.apply() intentionally returns null for drop events.
        return resumeEvents.handle((event, sink) -> {
                    Map<String, Object> map = eventMapper.apply(event);
                    if (map != null && !map.isEmpty()) {
                        sink.next(map);
                    }
                });
    }

    /**
     * Build the approval completion event and register the pending approval.
     */
    private Map<String, Object> handleApprovalCompletion() {
        String approvalId = approvalService.registerPendingApproval(
                agent, hook, approvalMiddleware.getPendingToolUseBlocks(), agentId, sessionId);
        return Map.of(
                "type", "pending_approval",
                "approvalId", approvalId,
                "agentId", agentId != null ? agentId : "",
                "toolCalls", approvalMiddleware.getPendingToolCallsForSse(),
                "timestamp", System.currentTimeMillis()
        );
    }

    /**
     * Convert an AgentEvent from streamEvents() into an SSE-compatible Map.
     * Delegates to {@link AgentEventMapper} which covers all 30 AgentEventType values.
     * Returns null for events that should be silently consumed.
     *
     * <p>For {@code tool_start} events, post-processes the mapper output to inject MCP
     * provenance ({@code isMcp}, {@code mcpName}) by reverse-looking-up the tool name in
     * the agent's toolkit. The mapper itself is a pure function without toolkit access, so
     * this enrichment happens here where the agent (and its toolkit) is in scope.
     */
    private Map<String, Object> mapAgentEvent(AgentEvent event) {
        Map<String, Object> map = eventMapper.apply(event);
        if (map != null && "tool_start".equals(map.get("type"))) {
            enrichWithMcpProvenance(map);
        }
        return map;
    }

    /**
     * If the tool named in the event map is an MCP tool (registered via McpClientManager),
     * inject {@code isMcp=true} and {@code mcpName=<server name>} so the frontend trace
     * panel can distinguish MCP calls from local @Tool calls.
     */
    private void enrichWithMcpProvenance(Map<String, Object> map) {
        String toolName = (String) map.get("toolName");
        if (toolName == null || toolName.isEmpty()) {
            return;
        }
        try {
            AgentTool tool = agent.getToolkit().getTool(toolName);
            if (tool instanceof ToolBase tb && tb.isMcp()) {
                map.put("isMcp", true);
                String mcpName = tb.getMcpName();
                if (mcpName != null && !mcpName.isEmpty()) {
                    map.put("mcpName", mcpName);
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve MCP provenance for tool '{}': {}", toolName, e.getMessage());
        }
    }

    /**
     * Detect and remove stale pending tool calls from the agent's conversation context.
     *
     * <p>A "stale pending tool call" is a {@link ToolUseBlock} in the last assistant message
     * that has no corresponding {@link ToolResultBlock} in context — meaning the previous
     * request was interrupted (timeout, cancellation, error) after the LLM emitted the tool
     * call but before the acting phase could execute it.
     *
     * <p>Without this cleanup, AgentScope's {@code enablePendingToolRecovery} would
     * auto-generate a synthetic error result on the next call, which pollutes the
     * conversation with misleading "[ERROR] Previous tool execution failed" messages.
     * Instead, we remove the orphaned assistant message entirely so the agent starts
     * fresh from the last clean state.
     *
     * <p>This is a no-op when there are no stale pending tool calls, when the agent has no
     * state (stateless mode with fresh store), or when the state accessor is unavailable.
     */
    private void clearStalePendingToolCalls() {
        try {
            io.agentscope.core.state.AgentState state = agent.getAgentState();
            if (state == null) {
                return;
            }
            List<Msg> context = state.contextMutable();
            if (context == null || context.isEmpty()) {
                return;
            }

            // Collect all tool result IDs already present in context
            Set<String> resolvedToolCallIds = context.stream()
                    .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                    .map(ToolResultBlock::getId)
                    .collect(Collectors.toSet());

            // Walk context backwards; remove trailing assistant messages whose ToolUseBlocks
            // have no matching results. We only remove the *last* contiguous run of orphaned
            // assistant messages — earlier messages with results are kept intact.
            List<Msg> toRemove = new ArrayList<>();
            for (int i = context.size() - 1; i >= 0; i--) {
                Msg msg = context.get(i);
                if (msg.getRole() != MsgRole.ASSISTANT) {
                    break; // Stop at the first non-assistant message (e.g., user or tool result)
                }
                List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                if (toolCalls.isEmpty()) {
                    break; // Assistant message without tool calls — not an orphan
                }
                boolean allUnresolved = toolCalls.stream()
                        .map(ToolUseBlock::getId)
                        .noneMatch(resolvedToolCallIds::contains);
                if (!allUnresolved) {
                    break; // At least one tool call was resolved — not stale
                }
                toRemove.add(msg);
            }

            if (toRemove.isEmpty()) {
                return;
            }

            // Remove stale messages (toRemove is in reverse order; remove from the end)
            for (Msg msg : toRemove) {
                context.remove(context.size() - 1);
            }

            List<String> staleToolNames = toRemove.stream()
                    .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                    .map(ToolUseBlock::getName)
                    .toList();
            log.warn("Cleared {} stale pending tool call(s) from previous request for agent: {} (tools: {})",
                    staleToolNames.size(), agent.getName(), staleToolNames);
        } catch (Exception e) {
            // Defensive: if anything goes wrong accessing agent state, log and continue
            // without cleanup. The framework's own pending-tool-recovery will still fire
            // as a fallback, so we degrade gracefully rather than breaking the request.
            log.debug("Could not clear stale pending tool calls for agent: {} ({})",
                    agent.getName(), e.getMessage());
        }
    }

    @Override
    public void close() {
        hook.reset();
        if (onClose != null) {
            onClose.run();
        }
        log.debug("AgentRuntime closed for agent: {}", agent.getName());
    }
}
