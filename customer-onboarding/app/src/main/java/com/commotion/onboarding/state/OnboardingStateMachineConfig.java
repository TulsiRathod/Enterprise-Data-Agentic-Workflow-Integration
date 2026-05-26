package com.commotion.onboarding.state;

import com.commotion.onboarding.schema.OnboardingEvent;
import com.commotion.onboarding.schema.OnboardingState;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

@Configuration
@EnableStateMachineFactory
public class OnboardingStateMachineConfig
        extends StateMachineConfigurerAdapter<OnboardingState, OnboardingEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<OnboardingState, OnboardingEvent> states)
            throws Exception {
        states.withStates()
                .initial(OnboardingState.INGESTED)
                .states(EnumSet.allOf(OnboardingState.class))
                .end(OnboardingState.DONE)
                .end(OnboardingState.FAILED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OnboardingState, OnboardingEvent> transitions)
            throws Exception {
        transitions
                .withExternal()
                    .source(OnboardingState.INGESTED).target(OnboardingState.PARSED)
                    .event(OnboardingEvent.PARSE_OK)
                .and().withExternal()
                    .source(OnboardingState.INGESTED).target(OnboardingState.FAILED)
                    .event(OnboardingEvent.PARSE_FAIL)
                .and().withExternal()
                    .source(OnboardingState.PARSED).target(OnboardingState.VALIDATED)
                    .event(OnboardingEvent.VALIDATE_OK)
                .and().withExternal()
                    .source(OnboardingState.PARSED).target(OnboardingState.FAILED)
                    .event(OnboardingEvent.VALIDATE_FAIL)
                .and().withExternal()
                    .source(OnboardingState.VALIDATED).target(OnboardingState.WRITTEN)
                    .event(OnboardingEvent.WRITE_OK)
                .and().withExternal()
                    .source(OnboardingState.VALIDATED).target(OnboardingState.FAILED)
                    .event(OnboardingEvent.WRITE_FAIL)
                .and().withExternal()
                    .source(OnboardingState.WRITTEN).target(OnboardingState.RECONCILED)
                    .event(OnboardingEvent.RECONCILE_OK)
                .and().withExternal()
                    .source(OnboardingState.WRITTEN).target(OnboardingState.FAILED)
                    .event(OnboardingEvent.RECONCILE_FAIL);
    }
}
