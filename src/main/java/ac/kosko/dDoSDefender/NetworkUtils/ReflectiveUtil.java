package ac.kosko.dDoSDefender.NetworkUtils;

import lombok.experimental.UtilityClass;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Utility class that simplifies reflection. It provides methods for getting fields by type
 * and retrieving values from them. It also collects fields from a class and its ancestors.
 */
@UtilityClass
public class ReflectiveUtil {

    /**
     * Retrieves a field from the given class that matches the provided type.
     *
     * @param klass The class to search through.
     * @param type The type of field being searched for.
     * @return The matching field, if found.
     * @throws NoSuchFieldException If no matching field is found.
     */
    public Field getFieldByType(final Class<?> klass, final Class<?> type) throws NoSuchFieldException {
        for (final Field field : getInheritedDeclaredFields(klass)) {
            if (type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }

        throw new NoSuchFieldException("Type: " + type.getName());
    }

    /**
     * Retrieves the value of a specific field from an object using reflection.
     *
     * @param object The object instance.
     * @param field The field whose value is being retrieved.
     * @return The value of the field.
     * @throws IllegalAccessException If reflection fails due to access control.
     */
    public <T> T getFieldValue(final Object object, final Field field) throws IllegalAccessException {
        field.setAccessible(true);
        return (T) field.get(object);
    }

    /**
     * Recursively collects all fields declared by a class and its superclasses.
     *
     * @param klass The class whose fields are being collected.
     * @return An array of Fields from the class and its superclasses.
     */
    private Field[] getInheritedDeclaredFields(final Class<?> klass) {
        if (klass.equals(Object.class)) return new Field[0];
        final Field[] inheritedFields = getInheritedDeclaredFields(klass.getSuperclass());
        final Field[] ownFields = klass.getDeclaredFields();
        final Field[] allFields = Arrays.copyOf(ownFields, ownFields.length + inheritedFields.length);
        System.arraycopy(inheritedFields, 0, allFields, ownFields.length, inheritedFields.length);

        return allFields;
    }
}