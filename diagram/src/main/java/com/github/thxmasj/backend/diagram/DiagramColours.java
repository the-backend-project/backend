package com.github.thxmasj.backend.diagram;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.Severity;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/// The colours of the text in the diagrams which is coloured, and how the diagrams are drawn in dark mode.
///
/// A diagram is shown in an `object` element, and a browser draws an embedded document of another colour scheme than
/// the page on an opaque background. The diagrams therefore take the colour scheme of the page, and in dark mode draw
/// their arrows, and the text on them, in light colours.
final class DiagramColours {

  private DiagramColours() {}

  /// A colour of text, in light and dark mode. The light one is used in the PlantUML source, which writes it in
  /// uppercase in the SVG.
  record Colour(String light, String dark) {}

  /// The event of a transition which reverses another, in the ER diagram
  static final Colour REVERSAL = new Colour("#7A0000", "#FF8A80");

  // Yellow can't be read on the light page, so a warning is dark amber in light mode
  private static final Map<Severity, Colour> SEVERITIES = Map.of(
      Severity.Warning, new Colour("#7A5200", "#F2C94C"),
      Severity.Error, new Colour("#C45200", "#FF9640"),
      Severity.Critical, new Colour("#B00000", "#FF5C5C")
  );

  /// The colour of the name of an event accepted with the severity, or null for [Severity#Info], whose events are
  /// named in the default colour
  static Colour of(Severity severity) {
    return SEVERITIES.get(severity);
  }

  private static final String ARROW = "#a3b06a";
  private static final String TEXT = "#e3e8d0";

  /// CSS for the `style` element of a diagram's SVG
  static final String STYLE = """
      svg{color-scheme:light dark}
      @media (prefers-color-scheme:dark){
      g.link path,g.start_entity ellipse{stroke:%1$s!important}
      g.link polygon,g.start_entity ellipse{fill:%1$s}
      g.link text[fill="#000"]{fill:%2$s}
      """.formatted(ARROW, TEXT)
      + colouredText("g.link")
      + "}\n";

  /// CSS for the `style` element of a sequence diagram's SVG, whose messages are groups of class `message`. The
  /// lifelines are drawn in the colour of the arrows, as their dark border colour can't be seen on a dark page.
  static final String SEQUENCE_STYLE = """
      svg{color-scheme:light dark}
      @media (prefers-color-scheme:dark){
      g.message line,g.message polygon,g.participant-lifeline line{stroke:%1$s!important}
      g.message polygon{fill:%1$s}
      g.message text[fill="#000"]{fill:%2$s}
      """.formatted(ARROW, TEXT)
      + colouredText("g.message")
      + "}\n";

  /// The rules giving the coloured text in the groups of the selector its colour in dark mode
  private static String colouredText(String group) {
    return Stream.concat(Stream.of(REVERSAL), SEVERITIES.values().stream())
        .map(c -> group + " text[fill=\"" + c.light() + "\"]{fill:" + c.dark() + "}\n")
        .sorted()
        .collect(joining());
  }

  /// CSS for the `style` element of an SVG written by Graphviz, whose edges are groups of class `edge`
  static final String GRAPHVIZ_STYLE = """
      svg{color-scheme:light dark}
      @media (prefers-color-scheme:dark){
      g.edge path{stroke:%1$s}
      g.edge polygon{fill:%1$s;stroke:%1$s}
      g.edge text{fill:%2$s}
      }
      """.formatted(ARROW, TEXT);

  /// Adds [#GRAPHVIZ_STYLE] to an SVG written by Graphviz, which has no `defs` element
  static String addGraphvizStyle(String svg) {
    return svg.replaceFirst("(<svg[^>]*>)", "$1<style type=\"text/css\">" + Matcher.quoteReplacement(GRAPHVIZ_STYLE) + "</style>");
  }

  /// Adds [#STYLE] to an SVG written by PlantUML
  static String addStyle(String svg) {
    return addStyle(svg, STYLE);
  }

  /// Adds [#SEQUENCE_STYLE] to a sequence diagram written by PlantUML
  static String addSequenceStyle(String svg) {
    return addStyle(svg, SEQUENCE_STYLE);
  }

  private static String addStyle(String svg, String style) {
    return svg.replaceFirst("<defs/>", "<defs></defs>")
        .replaceFirst("<defs>", "<defs><style type=\"text/css\">" + Matcher.quoteReplacement(style) + "</style>");
  }
}
