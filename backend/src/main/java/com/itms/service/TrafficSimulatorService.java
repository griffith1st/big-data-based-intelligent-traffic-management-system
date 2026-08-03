package com.itms.service;

import com.itms.entity.TrafficData;

import java.util.List;

public interface TrafficSimulatorService {

    void start();

    void stop();

    boolean isRunning();

    List<TrafficData> generateOnce();
}
