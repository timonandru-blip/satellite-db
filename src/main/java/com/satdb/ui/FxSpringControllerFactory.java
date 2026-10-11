package com.satdb.ui;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javafx.util.Callback;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

/**
 * Мост Spring → JavaFX (шаблон 06-spring-boot-architecture/SKILL.md, п.3).
 *
 * Используется как {@link FXMLLoader#setControllerFactory(Callback)}: вместо
 * стандартного безаргументного new Controller() фабрика вызывает конструктор
 * контроллера с параметрами, разрешая каждый из них из Spring-контекста
 * (constructor injection — тот же стиль, что и в сервисном слое).
 *
 * Дополнительно поддерживается @FxInject — полевое внедрение бинов для
 * контроллеров, которым нужен безаргументный конструктор (например, тестовые
 * двойники). Кэш конструкторов избегает рефлексии при каждом открытии окна.
 */
public final class FxSpringControllerFactory implements Callback<Class<?>, Object> {

    /** Маркер для полевого внедрения бина из контекста (аналог @Autowired в FXML-контроллерах). */
    public @interface FxInject {
        /** Имя бина; пусто — поиск по типу. */
        String value() default "";
    }

    private static final Map<Class<?>, Constructor<?>> CTOR_CACHE = new ConcurrentHashMap<>();

    private final ListableBeanFactory beanFactory;

    public FxSpringControllerFactory(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object call(Class<?> controllerType) {
        try {
            return constructWithInjection(controllerType);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Не удалось создать FXML-контроллер " + controllerType.getName()
                            + " через Spring-контекст", e);
        }
    }

    private Object constructWithInjection(Class<?> type) throws ReflectiveOperationException {
        Constructor<?> ctor = resolveConstructor(type);
        Class<?>[] paramTypes = ctor.getParameterTypes();

        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = resolveBean(paramTypes[i]);
        }
        ctor.setAccessible(true);
        Object controller = ctor.newInstance(args);
        injectFields(controller);
        return controller;
    }

    private Constructor<?> resolveConstructor(Class<?> type) {
        return CTOR_CACHE.computeIfAbsent(type, t -> {
            Constructor<?>[] ctors = t.getDeclaredConstructors();
            if (ctors.length == 1) {
                return ctors[0];
            }
            // несколько конструкторов: берём тот, все параметры которого — бины контекста
            for (Constructor<?> c : ctors) {
                boolean allResolvable = true;
                for (Class<?> p : c.getParameterTypes()) {
                    if (resolveBeanQuietly(p) == null) {
                        allResolvable = false;
                        break;
                    }
                }
                if (allResolvable) {
                    return c;
                }
            }
            throw new IllegalStateException(
                    "Нет конструктора с полностью разрешимыми Spring-бинами: " + t.getName());
        });
    }

    private Object resolveBean(Class<?> type) {
        Object bean = resolveBeanQuietly(type);
        if (bean == null) {
            throw new NoSuchBeanDefinitionException(type);
        }
        return bean;
    }

    private Object resolveBeanQuietly(Class<?> type) {
        try {
            Map<String, ?> beans = beanFactory.getBeansOfType(type);
            if (beans.isEmpty()) {
                return null;
            }
            return beans.size() == 1
                    ? beans.values().iterator().next()
                    : beanFactory.getBean(type); // пусть Spring сам разберётся с приоритетами
        } catch (NoSuchBeanDefinitionException e) {
            return null;
        }
    }

    /** Полевое внедрение: поля, помеченные @FxInject. */
    private void injectFields(Object controller) throws ReflectiveOperationException {
        for (Class<?> c = controller.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                FxInject ann = f.getAnnotation(FxInject.class);
                if (ann == null) {
                    continue;
                }
                Object bean = ann.value().isEmpty()
                        ? resolveBean(f.getType())
                        : beanFactory.getBean(ann.value(), f.getType());
                f.setAccessible(true);
                f.set(controller, bean);
            }
        }
    }
}
