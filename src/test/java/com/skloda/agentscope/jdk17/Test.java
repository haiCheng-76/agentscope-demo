package com.skloda.agentscope.jdk17;

public class Test {

    @org.junit.jupiter.api.Test
    public void varTest() {
        var name = "lhc";
        System.out.println(name);
    }


    @org.junit.jupiter.api.Test
    public void switchTest() {
        var score = 60;
        switch (score) {
            case 0 -> System.out.println("0");
            case 10 -> System.out.println("10");
            default -> System.out.println("default");
        }
    }

    @org.junit.jupiter.api.Test
    public void switchTest1() {
        var score = 100;
        var level = switch (score) {
            case 0 -> "0";
            case 10 -> "10";
            case 100 -> {
                var max = Math.max(score, 100);
                yield "MAX";
            }
            default -> "default";
        };
        System.out.println(level);
    }

    @org.junit.jupiter.api.Test
    public void textBlack() {
        var msg = """
                这是第一段话
                这是第二段话
                这是第三段话
                """;
        System.out.println(msg);
    }

    @org.junit.jupiter.api.Test
    public void instanceofTest() {
        Object name = "lhc";
        if (name instanceof String str) {
            System.out.println(str);
        }
        User user = new User("lhc", 18);
        System.out.println(user);
        System.out.println(user.name());
        System.out.println(user.age());
    }

    public record User(String name, int age) {
    }


}
