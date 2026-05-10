package org.javaup.ai.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @program: 大麦-ai智能服务项目。 添加 阿星不是程序员 微信，添加时备注 ai 来获取项目的完整资料
 * @description: Qdrant 官方 gRPC 客户端配置
 * @author: 阿星不是程序员
 **/
@Slf4j
@Configuration
public class QdrantClientConfiguration {

    @Value("${damai.ai.qdrant.grpc-host:127.0.0.1}")
    private String grpcHost;

    @Value("${damai.ai.qdrant.grpc-port:16334}")
    private int grpcPort;

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient() {
        log.info("初始化 Qdrant gRPC 客户端: {}:{}", grpcHost, grpcPort);
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(grpcHost, grpcPort, false).build();
        return new QdrantClient(grpcClient);
    }
}
