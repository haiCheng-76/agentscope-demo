package com.skloda.agentscope.observer;

import java.util.ArrayList;
import java.util.List;

public class OrderService implements Subject {

    private List<Observer> observers = new ArrayList<>();

    @Override
    public void attach(Observer observer) {
        observers.add(observer);
    }

    @Override
    public void detach(Observer observer) {
        observers.remove(observer);
    }

    @Override
    public void notifyObservers() {
        for (Observer observer : observers) {
            observer.update(this);
        }
    }

    public void createOrder() {
        notifyObservers();
    }
}
