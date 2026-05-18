package org.javaup.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class AssistantThreadPoolConfiguration {

    @Bean("assistantRunExecutor")
    public ThreadPoolExecutor assistantRunExecutor(ThreadPoolProperties properties) {
        var p = properties.getAssistant();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                p.getCorePoolSize(),
                p.getMaxPoolSize(),
                p.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(p.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "assistant-run-");
                    thread.setDaemon(true);
                    return thread;
                },
                new RejectedExecutionHandler() {
                    @Override
                    public void rejectedExecution(Runnable runnable, ThreadPoolExecutor exec) {
                        log.warn("Assistant run queue full ({}/{}), rejecting task with CallerRunsPolicy",
                                exec.getQueue().size(), p.getQueueCapacity());
                        new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(runnable, exec);
                    }
                }
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
