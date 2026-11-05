package com.skloda.agentscope.observer1;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ItObserver implements Observer {


    @Override
    public void exec(String goodName) {
        log.info("IT　部门收到商品{}的通知，准备发货", goodName);
    }
}
