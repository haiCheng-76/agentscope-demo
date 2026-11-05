package com.skloda.agentscope.observer1;

import java.util.ArrayList;
import java.util.List;

/**
 * 购物车主题类，实现了主题接口{@link Subject}，用于管理观察者并通知它们
 */
public class ShoppingCarSubject implements Subject {

    private final List<Observer> list = new ArrayList<>();

    private final  List<String> shoppingCar = new ArrayList<>();


    @Override
    public void add(Observer observer) {
        list.add(observer);
    }

    @Override
    public void remove(Observer observer) {
        list.remove(observer);
    }


    @Override
    public void notifyObservers(String goodName) {
        for (Observer observer : list) {
            observer.exec(goodName);
        }
    }

    public void addGood(String goodName) {
        shoppingCar.add(goodName);
        notifyObservers(goodName);
    }
}
