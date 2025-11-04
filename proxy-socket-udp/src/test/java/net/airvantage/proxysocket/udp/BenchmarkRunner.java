/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Simple runner for JMH benchmarks.
 */
public class BenchmarkRunner {
    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(BenchmarkProxyDatagramSocket.class.getSimpleName())
                .forks(1)
                .build();

        new Runner(opt).run();
    }
}

