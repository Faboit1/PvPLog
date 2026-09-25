package top.cheesesmp.duelcore.ui.anim;

/**
 * What an animation draws on. Each player runs at most one animation per channel: starting another one on the same
 * channel replaces it, so two systems never fight over the same action bar or title.
 */
public enum Channel {
    ACTION_BAR,
    TITLE,
    /** A dialog re-shown frame by frame (e.g. an animated progress bar in the queue menu). */
    DIALOG,
    BOSS_BAR,
    /** Sound sequences (arpeggios, flourishes): a new one cuts the old one off instead of layering on it. */
    SOUND
}
