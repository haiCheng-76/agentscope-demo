package com.skloda.agentscope;

import com.alibaba.dashscope.utils.JsonUtils;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.*;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

@Slf4j
public class test {


    @Test
    public void test() {
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("deepseek-v4-pro-0813")
                .build();


        ReActAgent reActAgent = ReActAgent.builder()
                .name("test")
                .sysPrompt("你是一个AI助手")
                .model(chatModel)
                .defaultSessionId(UUID.randomUUID().toString())
                .build();

        Flux<AgentEvent> flux = reActAgent.streamEvents("什么事斐波那契");
        flux.subscribe(
                item -> {
                    if (item instanceof AgentStartEvent startEvent) {
                        log.info("agent 开始运行");
                        return;
                    }

                    if (item instanceof ModelCallStartEvent event) {
                        log.info("llm 开始调用");
                        return;
                    }
                    if (item instanceof ThinkingBlockStartEvent event) {
                        log.info("开始执行思考模块");
                        return;
                    }

                    if (item instanceof ThinkingBlockDeltaEvent event) {
                        log.info("思考内容; content: {}", event.getDelta());
                        return;
                    }

                    if (item instanceof ThinkingBlockEndEvent event) {
                        log.info("思考结束");
                        return;
                    }


                    if (item instanceof TextBlockStartEvent event) {
                        log.info("开始生产内容");
                        return;
                    }

                    if (item instanceof TextBlockDeltaEvent event) {
                        log.info("内容; content: {}", event.getDelta());
                        return;
                    }

                    if (item instanceof TextBlockEndEvent event) {
                        log.info("结束生产内容");
                        return;
                    }

                    if (item instanceof ModelCallEndEvent event) {
                        log.info("llm 结束调用");
                        return;
                    }

                    if (item instanceof AgentResultEvent event) {
                        Map<String, Object> metadata = event.getMetadata();
                        log.info("AGENT_RESULT; metadata:｛｝｝", JsonUtils.toJson(metadata));
                        return;
                    }

                    if (item instanceof AgentEndEvent startEvent) {
                        log.info("agent 结束运行");
                        return;
                    }

                },
                error -> log.warn("error: {}", error.getMessage())
        );
    }
}
