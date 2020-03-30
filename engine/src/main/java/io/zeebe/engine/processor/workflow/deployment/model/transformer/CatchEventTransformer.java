/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow.deployment.model.transformer;

import io.zeebe.el.Expression;
import io.zeebe.el.ExpressionLanguage;
import io.zeebe.engine.processor.workflow.deployment.model.BpmnStep;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableCatchEventElement;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableMessage;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableWorkflow;
import io.zeebe.engine.processor.workflow.deployment.model.transformation.ModelElementTransformer;
import io.zeebe.engine.processor.workflow.deployment.model.transformation.TransformContext;
import io.zeebe.model.bpmn.instance.CatchEvent;
import io.zeebe.model.bpmn.instance.ErrorEventDefinition;
import io.zeebe.model.bpmn.instance.EventDefinition;
import io.zeebe.model.bpmn.instance.Message;
import io.zeebe.model.bpmn.instance.MessageEventDefinition;
import io.zeebe.model.bpmn.instance.TimerEventDefinition;
import io.zeebe.model.bpmn.util.time.Interval;
import io.zeebe.model.bpmn.util.time.RepeatingInterval;
import io.zeebe.model.bpmn.util.time.TimeDateTimer;
import io.zeebe.model.bpmn.util.time.Timer;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.util.buffer.BufferUtil;
import java.util.List;
import java.util.Optional;

public final class CatchEventTransformer implements ModelElementTransformer<CatchEvent> {

  @Override
  public Class<CatchEvent> getType() {
    return CatchEvent.class;
  }

  @Override
  public void transform(final CatchEvent element, final TransformContext context) {
    final ExecutableWorkflow workflow = context.getCurrentWorkflow();
    final ExecutableCatchEventElement executableElement =
        workflow.getElementById(element.getId(), ExecutableCatchEventElement.class);

    executableElement.bindLifecycleState(
        WorkflowInstanceIntent.ELEMENT_COMPLETED, BpmnStep.FLOWOUT_ELEMENT_COMPLETED);

    if (!element.getEventDefinitions().isEmpty()) {
      transformEventDefinition(element, context, executableElement);
    }
  }

  private void transformEventDefinition(
      final CatchEvent element,
      final TransformContext context,
      final ExecutableCatchEventElement executableElement) {
    final EventDefinition eventDefinition = element.getEventDefinitions().iterator().next();
    if (eventDefinition instanceof MessageEventDefinition) {
      transformMessageEventDefinition(
          context, executableElement, (MessageEventDefinition) eventDefinition);

    } else if (eventDefinition instanceof TimerEventDefinition) {
      final var expressionLanguage = context.getExpressionLanguage();
      final var timerDefinition = (TimerEventDefinition) eventDefinition;
      transformTimerEventDefinition(expressionLanguage, executableElement, timerDefinition);

    } else if (eventDefinition instanceof ErrorEventDefinition) {
      transformErrorEventDefinition(
          context, executableElement, (ErrorEventDefinition) eventDefinition);
    }
  }

  private void transformMessageEventDefinition(
      final TransformContext context,
      final ExecutableCatchEventElement executableElement,
      final MessageEventDefinition messageEventDefinition) {

    final Message message = messageEventDefinition.getMessage();
    final ExecutableMessage executableMessage = context.getMessage(message.getId());
    executableElement.setMessage(executableMessage);
  }

  private void transformTimerEventDefinition(
      final ExpressionLanguage expressionLanguage,
      final ExecutableCatchEventElement executableElement,
      final TimerEventDefinition timerEventDefinition) {

    final Timer timer; // todo: remove this timer when start events have feel expression support
    final Expression expression;
    final TimerType type;
    if (timerEventDefinition.getTimeDuration() != null) {
      final String duration = timerEventDefinition.getTimeDuration().getTextContent();
      expression = expressionLanguage.parseExpression(duration);
      type = TimerType.DURATION;
      if (expression.isStatic()) {
        timer = new RepeatingInterval(1, Interval.parse(expression.getExpression()));
      } else {
        timer = null;
      }
    } else if (timerEventDefinition.getTimeCycle() != null) {
      final String cycle = timerEventDefinition.getTimeCycle().getTextContent();
      expression = expressionLanguage.parseExpression(cycle);
      type = TimerType.CYCLE;
      if (expression.isStatic()) {
        timer = RepeatingInterval.parse(expression.getExpression());
      } else {
        timer = null;
      }
    } else {
      final String timeDate = timerEventDefinition.getTimeDate().getTextContent();
      expression = expressionLanguage.parseExpression(timeDate);
      type = TimerType.TIME_DATE;
      if (expression.isStatic()) {
        timer = TimeDateTimer.parse(expression.getExpression());
      } else {
        timer = null;
      }
    }

    executableElement.setTimerFactory(
        (expressionProcessor, scopeKey) -> {
          switch (type) {
            case DURATION:
              return Optional.of(
                      expressionProcessor.evaluateIntervalExpression(expression, scopeKey))
                  .map(right -> new RepeatingInterval(1, right))
                  .orElseThrow();
            case CYCLE:
              return Optional.of(expressionProcessor.evaluateStringExpression(expression, scopeKey))
                  .map(BufferUtil::bufferAsString)
                  .map(RepeatingInterval::parse)
                  .orElseThrow();
            case TIME_DATE:
              return Optional.of(
                      expressionProcessor.evaluateIntervalExpression(expression, scopeKey))
                  .map(TimeDateTimer::new)
                  .orElseThrow();
            default:
              final var expectedTypes =
                  List.of(TimerType.DURATION, TimerType.CYCLE, TimerType.TIME_DATE);
              throw new IllegalStateException(
                  "Unexpected timer type '" + type + "'; expected one of " + expectedTypes);
          }
        });

    executableElement.setTimer(timer);
  }

  private void transformErrorEventDefinition(
      final TransformContext context,
      final ExecutableCatchEventElement executableElement,
      final ErrorEventDefinition errorEventDefinition) {

    final var error = errorEventDefinition.getError();
    final var executableError = context.getError(error.getId());
    executableElement.setError(executableError);
  }

  private enum TimerType {
    DURATION,
    CYCLE,
    TIME_DATE
  }
}
