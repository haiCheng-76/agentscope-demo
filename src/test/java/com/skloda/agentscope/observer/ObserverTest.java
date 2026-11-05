package com.skloda.agentscope.observer;

public class ObserverTest {

    public static void main(String[] args) {
        OrderService orderService = new OrderService();
        ObserverOne observerOne = new ObserverOne();

        orderService.attach(observerOne);
        orderService.createOrder();
    }
}
