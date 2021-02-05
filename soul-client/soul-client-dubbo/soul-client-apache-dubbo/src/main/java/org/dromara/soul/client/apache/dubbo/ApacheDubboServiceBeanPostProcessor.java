/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.soul.client.apache.dubbo;

import com.google.gson.Gson;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.spring.ServiceBean;
import org.dromara.soul.client.core.disruptor.SoulClientRegisterEventPublisher;
import org.dromara.soul.client.core.register.SoulClientRegisterRepositoryFactory;
import org.dromara.soul.client.dubbo.common.annotation.SoulDubboClient;
import org.dromara.soul.client.dubbo.common.dto.DubboRpcExt;
import org.dromara.soul.register.client.api.SoulClientRegisterRepository;
import org.dromara.soul.register.common.config.SoulRegisterCenterConfig;
import org.dromara.soul.register.common.dto.MetaDataDTO;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * The Apache Dubbo ServiceBean PostProcessor.
 *
 * @author xiaoyu
 */
@Slf4j
@SuppressWarnings("all")
public class ApacheDubboServiceBeanPostProcessor implements ApplicationListener<ContextRefreshedEvent> {
    
    private SoulClientRegisterEventPublisher soulClientRegisterEventPublisher = SoulClientRegisterEventPublisher.getInstance();
    
    private final AtomicBoolean registered = new AtomicBoolean(false);
    
    private Gson gson = new Gson();
    
    private ExecutorService executorService;
    
    private String contextPath;
    
    private String appName;
    
    public ApacheDubboServiceBeanPostProcessor(final SoulRegisterCenterConfig config) {
        Properties props = config.getProps();
        String contextPath = props.getProperty("contextPath");
        String appName = props.getProperty("appName");
        if (StringUtils.isEmpty(contextPath)) {
            throw new RuntimeException("apache dubbo client must config the contextPath");
        }
        this.contextPath = contextPath;
        this.appName = appName;
        executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        SoulClientRegisterRepository soulClientRegisterRepository = SoulClientRegisterRepositoryFactory.newInstance(config);
        soulClientRegisterEventPublisher.start(soulClientRegisterRepository);
    }

    private void handler(final ServiceBean serviceBean) {
        Class<?> clazz = serviceBean.getRef().getClass();
        if (ClassUtils.isCglibProxyClass(clazz)) {
            String superClassName = clazz.getGenericSuperclass().getTypeName();
            try {
                clazz = Class.forName(superClassName);
            } catch (ClassNotFoundException e) {
                log.error(String.format("class not found: %s", superClassName));
                return;
            }
        }
        final Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(clazz);
        for (Method method : methods) {
            SoulDubboClient soulDubboClient = method.getAnnotation(SoulDubboClient.class);
            if (Objects.nonNull(soulDubboClient)) {
                soulClientRegisterEventPublisher.publishEvent(buildMetaDataDTO(serviceBean, soulDubboClient, method));
            }
        }
    }

    private MetaDataDTO buildMetaDataDTO(final ServiceBean serviceBean, final SoulDubboClient soulDubboClient, final Method method) {
        String appName = this.appName;
        if (StringUtils.isEmpty(appName)) {
            appName = serviceBean.getApplication().getName();
        }
        String path = contextPath + soulDubboClient.path();
        String desc = soulDubboClient.desc();
        String serviceName = serviceBean.getInterface();
        String configRuleName = soulDubboClient.ruleName();
        String ruleName = ("".equals(configRuleName)) ? path : configRuleName;
        String methodName = method.getName();
        Class<?>[] parameterTypesClazz = method.getParameterTypes();
        String parameterTypes = Arrays.stream(parameterTypesClazz).map(Class::getName).collect(Collectors.joining(","));
        return MetaDataDTO.builder()
                .appName(appName)
                .serviceName(serviceName)
                .methodName(methodName)
                .contextPath(contextPath)
                .path(path)
                .ruleName(ruleName)
                .pathDesc(desc)
                .parameterTypes(parameterTypes)
                .rpcExt(buildRpcExt(serviceBean))
                .rpcType("dubbo")
                .enabled(soulDubboClient.enabled())
                .build();
    }

    private String buildRpcExt(final ServiceBean serviceBean) {
        DubboRpcExt build = DubboRpcExt.builder()
                .group(StringUtils.isNotEmpty(serviceBean.getGroup()) ? serviceBean.getGroup() : "")
                .version(StringUtils.isNotEmpty(serviceBean.getVersion()) ? serviceBean.getVersion() : "")
                .loadbalance(StringUtils.isNotEmpty(serviceBean.getLoadbalance()) ? serviceBean.getLoadbalance() : Constants.DEFAULT_LOADBALANCE)
                .retries(Objects.isNull(serviceBean.getRetries()) ? Constants.DEFAULT_RETRIES : serviceBean.getRetries())
                .timeout(Objects.isNull(serviceBean.getTimeout()) ? Constants.DEFAULT_CONNECT_TIMEOUT : serviceBean.getTimeout())
                .url("")
                .build();
        return gson.toJson(build);
    }

    @Override
    public void onApplicationEvent(final ContextRefreshedEvent contextRefreshedEvent) {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        // Fix bug(https://github.com/dromara/soul/issues/415), upload dubbo metadata on ContextRefreshedEvent
        Map<String, ServiceBean> serviceBean = contextRefreshedEvent.getApplicationContext().getBeansOfType(ServiceBean.class);
        for (Map.Entry<String, ServiceBean> entry : serviceBean.entrySet()) {
            executorService.execute(() -> handler(entry.getValue()));
        }
    }
}
