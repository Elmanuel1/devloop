# Testability Patterns

Make code easy to unit test with mocks.

---

## 1. Constructor Injection (Required)

```java
// ❌ BAD — field injection, hard to mock
@Service
public class OrderService {
    @Autowired private OrderRepository repository;
    @Autowired private EmailService emailService;
}

// ✅ GOOD — constructor injection
@Service
public class OrderService {
    private final OrderRepository repository;
    private final EmailService emailService;

    public OrderService(OrderRepository repository, EmailService emailService) {
        this.repository = repository;
        this.emailService = emailService;
    }
}
```

---

## 2. Avoid Static Methods for Business Logic

```java
// ❌ BAD — static method, cannot mock
public class DateUtils {
    public static LocalDate today() { return LocalDate.now(); }
}

public class OrderService {
    public boolean isOverdue(Order order) {
        return order.getDueDate().isBefore(DateUtils.today()); // can't mock!
    }
}

// ✅ GOOD — injectable Clock
@Service
public class OrderService {
    private final Clock clock;
    public OrderService(Clock clock) { this.clock = clock; }

    public boolean isOverdue(Order order) {
        return order.getDueDate().isBefore(LocalDate.now(clock));
    }
}
```

---

## 3. Extract Complex Conditionals

```java
// ❌ BAD — complex logic buried
if (order.getCustomer().getType() == VIP
    && order.getTotal().compareTo(new BigDecimal("1000")) > 0
    && order.getItems().size() > 5) { ... }

// ✅ GOOD — extract to testable method
if (qualifiesForBulkDiscount(order)) { ... }

boolean qualifiesForBulkDiscount(Order order) {
    return order.getCustomer().getType() == VIP
        && order.getTotal().compareTo(new BigDecimal("1000")) > 0
        && order.getItems().size() > 5;
}
```

---

## 4. Avoid God Classes

**Signs:**
- > 500 lines
- > 10 dependencies
- Methods that don't use most fields

**Fix:** Split by responsibility into focused services.

---

## 5. Return Values Instead of Modifying Parameters

```java
// ❌ BAD — mutates input
public void enrichOrder(Order order) {
    order.setTax(calculateTax(order));
    order.setTotal(calculateTotal(order));
}

// ✅ GOOD — returns new object
public Order enrichOrder(Order order) {
    return order.toBuilder()
        .tax(calculateTax(order))
        .total(calculateTotal(order))
        .build();
}
```

