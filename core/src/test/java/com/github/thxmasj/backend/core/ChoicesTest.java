package com.github.thxmasj.backend.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.backend.core.TransitionModelBuilder.Choices;
import com.github.thxmasj.backend.core.TransitionModelBuilder.WithFilter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/// A transition with choices always ends in one of them, as the compiler sees to: after `when(...).then(...)` the
/// builder offers only more choices or `otherwise(...)`, so a transition can't fall through to its own output when none
/// of its predicates holds, which would store the event of the transition choosing
public class ChoicesTest {

  @Test
  public void choicesAreFollowedByMoreChoicesOrOtherwiseOnly() {
    assertThat(publicMethodNames(Choices.class)).containsOnly("when", "choice", "otherwise");
  }

  @Test
  public void aPredicateIsFollowedByTheTransitionItChoosesOnly() {
    assertThat(publicMethodNames(WithFilter.class)).containsOnly("then");
  }

  @Test
  public void choicesAreMadeByTheBuilderOnly() {
    assertThat(Choices.class.getConstructors()).isEmpty();
    assertThat(WithFilter.class.getConstructors()).isEmpty();
  }

  private static String[] publicMethodNames(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .map(Method::getName)
        .toArray(String[]::new);
  }

}
