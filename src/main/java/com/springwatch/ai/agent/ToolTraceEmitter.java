package com.springwatch.ai.agent;

/**
 * 编排层 - ReAct 工具轨迹发射器。
 * 由 AgentExecutor 按对话请求创建,经 ChatClient.toolContext 传入,
 * ToolTraceCallback 在每次工具调用前后回调,把"行动(参数)/观察(结果)"推入当前 SSE 流。
 */
public interface ToolTraceEmitter {

    /** toolContext 传递键:ToolTraceCallback 依此从上下文取出当前请求的发射器 */
    String KEY = ToolTraceEmitter.class.getName();

    ToolTraceEmitter NOOP = new ToolTraceEmitter() {
        @Override
        public void onToolCall(String toolName, String arguments) {
        }

        @Override
        public void onToolResult(String toolName, String result) {
        }
    };

    /** ReAct 行动:工具调用前 */
    void onToolCall(String toolName, String arguments);

    /** ReAct 观察:工具执行返回后 */
    void onToolResult(String toolName, String result);
}
