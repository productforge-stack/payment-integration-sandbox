package com.productforge.payments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.productforge.payments.sim.GatewaySimulator;
import com.productforge.payments.sim.Sandbox;
import com.productforge.payments.sim.Scenario;
import org.junit.Test;

/**
 * Many payment integrations are written in Java. This test calls the Kotlin library
 * from plain Java to prove the API works without any Kotlin-specific code.
 */
public class JavaInteropTest {

    @Test
    public void javaCanRunASaleAndReadTheResult() {
        GatewaySimulator simulator = new GatewaySimulator(() -> Scenario.APPROVE);
        PaymentService service = new PaymentService(Sandbox.gateway(simulator));

        PaymentResult result = service.sale(Money.parse("42.50"));

        assertTrue(result instanceof PaymentResult.Approved);
        PaymentResult.Approved approved = (PaymentResult.Approved) result;
        assertEquals(4250L, approved.getResponse().getAmountMinor());
        assertEquals("USD", approved.getResponse().getCurrency());
    }

    @Test
    public void javaSeesTheRecoveryFromATimeout() {
        GatewaySimulator simulator = new GatewaySimulator(() -> Scenario.TIMEOUT_AFTER_CHARGE);
        PaymentService service = new PaymentService(Sandbox.gateway(simulator), 3);

        PaymentResult result = service.sale(Money.parse("10.00", "USD"));

        assertTrue(result instanceof PaymentResult.Approved);
        assertTrue(((PaymentResult.Approved) result).getRecovered());
        assertEquals(1, simulator.getProcessedCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void javaGetsAClearErrorForABadAmount() {
        Money.parse("12.345");
    }
}
