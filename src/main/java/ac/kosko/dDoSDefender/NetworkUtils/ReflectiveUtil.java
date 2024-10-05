package ac.kosko.dDoSDefender.NetworkUtils;

import lombok.experimental.UtilityClass;

import java.lang.reflect.Field;
import java.util.Arrays;

@UtilityClass
public class ReflectiveUtil {

    public Field getFieldByType(final Class<?> klass, final Class<?> type) throws NoSuchFieldException {
        for (final Field field : getInheritedDeclaredFields(klass)) {
            if (type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException("Type: " + type.getName() + " not found in class: " + klass.getName());
    }

    public <T> T getFieldValue(final Object object, final Field field) throws IllegalAccessException {
        field.setAccessible(true);
        return (T) field.get(object);
    }

    private Field[] getInheritedDeclaredFields(final Class<?> klass) {
        if (klass.equals(Object.class)) return new Field[0];
        final Field[] inheritedFields = getInheritedDeclaredFields(klass.getSuperclass());
        final Field[] ownFields = klass.getDeclaredFields();
        final Field[] allFields = Arrays.copyOf(ownFields, ownFields.length + inheritedFields.length);
        System.arraycopy(inheritedFields, 0, allFields, ownFields.length, inheritedFields.length);

        return allFields;
    }
}