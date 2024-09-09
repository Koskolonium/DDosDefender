package ac.kosko.dDoSDefender.NetworkUtils;

import lombok.experimental.UtilityClass;

import java.lang.reflect.Field;
import java.util.Arrays;

@UtilityClass
public class ReflectiveUtil {

    public Field getFieldByType(final Class<?> klass, final Class<?> type) throws NoSuchFieldException {
        for (final Field field : getInheritedDeclaredFields(klass)) {
            if (type.isAssignableFrom(field.getType())) return field;
        }

        throw new NoSuchFieldException("Type: " + type.getName());
    }

    public <T> T getFieldValue(final Object object, final Field field) throws IllegalAccessException {
        field.setAccessible(true);
        return (T) field.get(object);
    }

    private Field[] getInheritedDeclaredFields(final Class<?> klass) {
        // Get all the inherited fields with recursion
        final Field[] inheritedFields;
        if (klass.equals(Object.class)) inheritedFields = new Field[0];
        else inheritedFields = getInheritedDeclaredFields(klass.getSuperclass());

        // Get all the fields specific to the provided class
        final Field[] ownFields = klass.getDeclaredFields();

        // Concentrate both our arrays
        final Field[] allFields = Arrays.copyOf(ownFields, ownFields.length + inheritedFields.length);
        System.arraycopy(inheritedFields, 0, allFields, ownFields.length, inheritedFields.length);

        return allFields;
    }
}