package com.skloda.agentscope.jdk17;

public interface Push {

    // 对外公开的默认方法
    default int add(int a, int b) {
        validate(a, b);
        return a + b;
    }

    default int subtract(int a, int b) {
        validate(a, b);
        return a - b;
    }

    // 私有方法：仅接口内部可用，实现类无法调用，也不属于 API 契约
    private void validate(int a, int b) {
        if (a < 0 || b < 0) {
            throw new IllegalArgumentException("参数不能为负数");
        }
    }
}
