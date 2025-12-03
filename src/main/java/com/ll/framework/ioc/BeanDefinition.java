package com.ll.framework.ioc;

import lombok.Getter;

@Getter
public class BeanDefinition {
    private final Class<?> beanClass;

    public BeanDefinition(Class<?> beanClass) {
        this.beanClass = beanClass;
    }
}
