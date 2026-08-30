package com.synctank.platform.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordFieldEditorTest {

    private final RecordFieldEditor editor = new RecordFieldEditor();

    /** Byte-for-byte OrderResponse.java as it exists on main. */
    private static final String ORDER_RESPONSE = """
            package com.synctank.orders.api;

            import io.swagger.v3.oas.annotations.media.Schema;

            public record OrderResponse(
                    Long id,
                    String customerName,
                    @Schema(minimum = "0") double amount,
                    @Schema(allowableValues = {"PENDING", "SHIPPED", "DELIVERED", "CANCELLED"}) String status
            ) {}
            """;

    /** Post-backlog OrderController — "PENDING" everywhere, per backlog Fix 7. */
    private static final String ORDER_CONTROLLER = """
            package com.synctank.orders.api;

            public class OrderController {
                public OrderResponse getOrder(Long id) {
                    return new OrderResponse(id, "Asha Rao", 249.50, "SHIPPED");
                }
                public java.util.List<OrderResponse> listOrders() {
                    return java.util.List.of(
                            new OrderResponse(1L, "Asha Rao", 249.50, "SHIPPED"),
                            new OrderResponse(2L, "Vikram Iyer", 89.00, "PENDING")
                    );
                }
            }
            """;

    @Test
    void appendsComponentAfterTheLastOne() {
        String edited = editor.addRecordComponent(ORDER_RESPONSE, "OrderResponse", "String", "customerEmail");

        assertThat(edited).contains("String status,\n        String customerEmail\n)");
        assertThat(edited).contains("package com.synctank.orders.api;");   // nothing else disturbed
    }

    @Test
    void annotationArgumentsDoNotConfuseTheParenScanner() {
        // @Schema(allowableValues = {...}) sits INSIDE the component list. A naive depth
        // counter would stop at its closing paren and corrupt the record.
        String edited = editor.addRecordComponent(ORDER_RESPONSE, "OrderResponse", "String", "customerEmail");

        assertThat(edited).contains("\"CANCELLED\"}) String status,");
        assertThat(edited.indexOf("customerEmail")).isGreaterThan(edited.indexOf("String status"));
    }

    @Test
    void detectsAnExistingComponent() {
        assertThat(editor.hasComponent(ORDER_RESPONSE, "OrderResponse", "customerName")).isTrue();
        assertThat(editor.hasComponent(ORDER_RESPONSE, "OrderResponse", "customerEmail")).isFalse();
    }

    @Test
    void doesNotMistakeATypeNameForAComponentName() {
        // "String" appears three times as a TYPE. It is never a component name.
        assertThat(editor.hasComponent(ORDER_RESPONSE, "OrderResponse", "String")).isFalse();
    }

    @Test
    void patchesEveryConstructorCallSite() {
        String patched = editor.appendConstructorArgument(ORDER_CONTROLLER, "OrderResponse", "null");

        assertThat(patched).contains("new OrderResponse(id, \"Asha Rao\", 249.50, \"SHIPPED\", null)");
        assertThat(patched).contains("new OrderResponse(1L, \"Asha Rao\", 249.50, \"SHIPPED\", null)");
        assertThat(patched).contains("new OrderResponse(2L, \"Vikram Iyer\", 89.00, \"PENDING\", null)");
        assertThat(patched.split("null\\)", -1).length - 1).isEqualTo(3);
    }

    @Test
    void rejectsTypesAndNamesOutsideTheAllowlist() {
        assertThat(editor.isAllowedType("BigDecimal")).isFalse();
        assertThat(editor.isAllowedType("double")).isFalse();
        assertThat(editor.isAllowedType("String")).isTrue();

        assertThat(editor.isValidFieldName("customer.email")).isFalse();
        assertThat(editor.isValidFieldName("class")).isFalse();
        assertThat(editor.isValidFieldName("CustomerEmail")).isFalse();
        assertThat(editor.isValidFieldName("customerEmail")).isTrue();
    }

    @Test
    void refusesARecordItCannotFind() {
        // This is audit Finding C in test form: CustomerResponse is nested inside
        // CustomerController, so no CustomerResponse.java exists to edit.
        assertThatThrownBy(() ->
                editor.addRecordComponent(ORDER_RESPONSE, "CustomerResponse", "String", "phone"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No top-level record");
    }
}