package io.github.yourimartin.gatewai.domain.model.shadow;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the LLM judge said of one would-hit (v4 D.2): would the cached answer
 * have been an acceptable answer, given the one the client actually received?
 */
public enum JudgeVerdict {

  /** The cached answer would have been acceptable. */
  YES,

  /** It would not: a wrong hit. */
  NO,

  /**
   * The judge could not tell — or answered something the rubric does not allow.
   * Excluded from the wrong-hit rate, and counted beside it.
   */
  UNSURE;

  private static final Pattern FIRST_WORD = Pattern.compile("[A-Za-z]+");

  /**
   * Reads a judge's reply: its first word, case and punctuation ignored. Anything
   * else is {@link #UNSURE}: a reply the rubric does not allow is not evidence
   * either way, and guessing which way it leaned would bias the rate.
   */
  public static JudgeVerdict parse(String reply) {
    if (reply == null) {
      return UNSURE;
    }
    Matcher word = FIRST_WORD.matcher(reply);
    if (!word.find()) {
      return UNSURE;
    }
    return switch (word.group().toUpperCase(Locale.ROOT)) {
      case "YES" -> YES;
      case "NO" -> NO;
      default -> UNSURE;
    };
  }
}
