package com.skloda.agentscope.observer1;

public class Test {

    public static void main(String[] args) {
        // 1. 定义购物车
        ShoppingCarSubject shoppingCarSubject = new ShoppingCarSubject();

        // 2. 定义购物车的观察者{@link ItObserver}
        ItObserver itObserver = new ItObserver();
        shoppingCarSubject.add(itObserver);

        // 3. 定义购物车的观察者{@link ExpressObserver}
        ExpressObserver expressObserver = new ExpressObserver();
        shoppingCarSubject.add(expressObserver);

        // 4. 定义购物车的观察者{@link StorehouseObserver}
        StorehouseObserver storehouseObserver = new StorehouseObserver();
        shoppingCarSubject.add(storehouseObserver);

        // 5. 向购物车添加商品，并通知所有观察者
        shoppingCarSubject.addGood("擦脸油");
    }
}
