package com.skloda.agentscope.observer1;


/**
 * 定义主题接口，提供添加、删除观察者和通知观察者的方法
 */
public interface Subject {
    /**
     * 添加观察者
     *
     * @param observer
     */
    void add(Observer observer);

    /**
     * 删除观察者
     *
     * @param observer
     */
    void remove(Observer observer);

    /**
     * 通知所有观察者
     */
    void notifyObservers(String goodName);
}
