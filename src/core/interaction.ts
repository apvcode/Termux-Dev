/**
 * Where a question for the person goes. In the terminal it is a picker on the screen; a session that is
 * driven from the phone (see remote/) installs its own handlers, so the same tools work without a terminal.
 */
export interface QuestionAsked {
  id?: string;
  question: string;
  options: string[];
  allowCustom?: boolean;
}

export interface InteractionHandlers {
  /** The answers in the order of the questions; an unanswered one is skipped. */
  askQuestions?: (questions: QuestionAsked[]) => Promise<Array<{ question: string; answer?: string }>>;
}

export const interaction: InteractionHandlers = {};
