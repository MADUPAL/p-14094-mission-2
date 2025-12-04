package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Component;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

public class ApplicationContext {

    private String basePackage;
    Map<String, BeanDefinition> beanDefinitionMap = new HashMap<>();
    Map<String, Object> singletonObjects = new HashMap<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    public void init() {
        scanAndRegister(basePackage);
    }

    /**   ===== scanAndRegister =====
     * 1. "com.ll" → "com/ll" 로 바꾼다.
     *    클래스패스에서 리소스 위치를 찾는다
     *    ===== scanDirectory =====
     * 2. 클래스패스에서 해당 디렉토리의 파일 목록을 가져온다.
     * 3. 재귀적으로 디렉토리를 돌면서 .class 파일을 찾는다.
     * 4. Class.forName(풀패키지명)으로 로딩.
     * 5. 인터페이스/추상 클래스는 제외하고, 나머지는 registerBean().
     * 6. @Component 가 붙은 클래스만 bean등록 대상
     *    clazz.isAnnotationPresent(Component.class)
     */
    private void scanAndRegister(String basePackage) {
        //com.ll -> com/ll
        String path = basePackage.replace(".", "/");

        //현재스레드를 기준 + 그 스레드가 속한 환경에 맞게 클래스패스에서 리소스 위치를 찾음
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = this.getClass().getClassLoader();
        }

        URL url = cl.getResource(path);
        if (url == null) {
            throw new RuntimeException("패키지를 찾을 수 없음: " + basePackage);
        }

        try {
            //File에는 string이나 URI만 들어갈 수 있음
            //URL: file:/Users/me/My%20Project/build/classes/com/ll
            //이 경우URI로 바꾸지 않으면 에러가 난다.

            //URL에는 파일 경로, JAR 내부 경로 등 다양한 형식이 가능하다.
            //File 시스템에서 사용하는 경로로 안정적으로 변환하려면
            //URL → URI → File 로 단계적으로 변환해야 한다
            File rootDir = new File(url.toURI());
            scanDirectory(rootDir, basePackage);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private void scanDirectory(File dir, String currentPackage) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                String subPackage = currentPackage + "." + file.getName();
                scanDirectory(file, subPackage);
            } else if (file.getName().endsWith(".class")) {
                String className = file.getName().substring(0, file.getName().length() - 6);

                // 내부 클래스(MyClass$1 이런거)는 스킵
                if (className.contains("$")) continue;

                String fullQualifiedClassName = currentPackage + "." + className;

                try {
                    Class<?> clazz = Class.forName(fullQualifiedClassName);
                    //인터페이스, 추상메서드, @Component 안붙어있는애는 패스
                    if (clazz.isInterface() ||
                            Modifier.isAbstract(clazz.getModifiers()) ||
                            !componentExist(clazz)) {
                        continue;
                    }

                    //MyShopRepository -> myShopRepository
                    String simpleName = clazz.getSimpleName();
                    String beanName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);

                    registerBean(beanName, clazz);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("클래스 로딩 실패: " + fullQualifiedClassName, e);
                }
            }
        }
    }

    /**
     * 이미 생성된 bean이 있으면 바로 반환
     * 없다면 새로 생성해서 singletonMap에 넣고 반환
     */
    public <T> T genBean(String beanName) {

        //이미 생성된 bean이 있으면 바로 반환
        if (singletonObjects.containsKey(beanName)) {
            return (T) singletonObjects.get(beanName);
        }
        //이미 생성된게 없다면 새로 생성해서 singleton에 넣고 반환
        BeanDefinition beanDefinition = beanDefinitionMap.get(beanName);
        if (beanDefinition == null) {
            throw new RuntimeException("beanDefinition 없음: " + beanName);
        }

        Object bean = createBean(beanDefinition.getBeanClass());
        singletonObjects.put(beanName, bean);

        return (T) bean;
    }

    /**
     * 리플렉션을 사용하여 해당 클래스의 생성자를 호출
     * 스프링 기본값 == 생성자 중에서 매개변수가 가장 많은 것을 호출
     * 인스턴스를 만들어 반환
     */
    private Object createBean(Class<?> clazz) {
        try {
            //모든 생성자 찾아오기
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            //매개변수 제일 많은 생성자 찾기
            Constructor<?> constructor = Arrays
                    .stream(constructors)
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();
            //생성자의 파라미터 타입 가져오기
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            //타입중에 클래스가 있으면 클래스 기본 빈 이름으로 바꿔서 컨테이너에서 꺼내거나 새로 생성
            Object[] args = Arrays.stream(parameterTypes)
                    .map(this::resolveDependency)
                    .toArray();
            //private 생성자도 접근가능하게 바꿔줌
            constructor.setAccessible(true);
            //매개변수로 인스턴스 만들어서 반환
            return constructor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("createBean 실패: " + clazz.getName(), e);
        }
    }

    /**
     * 클래스 맨 앞 글자만 소문자로 바꾼걸 기준으로 genBean
     */
    private Object resolveDependency(Class<?> type) {
        String simpleName = type.getSimpleName();
        String defaultBeanName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);

        return genBean(defaultBeanName);
    }

    public void registerBean(String name, Class<?> clazz) {
        beanDefinitionMap.put(name, new BeanDefinition(clazz));
    }

    private boolean componentExist(Class<?> clazz) {
        // 직접 @Component가 붙은 경우
        if (clazz.isAnnotationPresent(Component.class)) {
            return true;
        }

        // 다른 어노테이션이 붙었는데,
        // 그 어노테이션 타입에 @Component가 붙어있는 경우
        // (@Service, @Repository, @Configuration 등)
        for (Annotation annotation : clazz.getAnnotations()) {
            Class<? extends Annotation> annoType = annotation.annotationType();
            if (annoType.isAnnotationPresent(Component.class)) {
                return true;
            }
        }

        return false;
    }
}
