package com.skloda.agentscope.observer;

public class ObserverOne implements Observer {
    @Override
    public void update(Subject subject) {
        System.out.println("观察者收到通知，执行自己的操作");
    }
}
