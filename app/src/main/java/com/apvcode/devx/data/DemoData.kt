package com.apvcode.devx.data

import com.apvcode.devx.model.Block

/** Test fixtures for the placeholder assistant: cards the real assistant will send as JSON later. */
object DemoData {
    /**
     * A quiz the assistant could compose: inputs with a correct answer, a "check" button,
     * a score, and a "retry" button. Written exactly as the assistant would send it.
     */
    val quizUi = Block.Ui(
        """
        {
          "type": "card",
          "title": "📝 Карточки с вопросами",
          "subtitle": "Тест по программированию · 5 вопросов",
          "children": [
            {"type": "section", "title": "Вопрос 1", "children": [
              {"type": "text", "text": "Как называется язык программирования, который мы изучали?"},
              {"type": "input", "id": "q1", "placeholder": "Впиши свой ответ…", "answer": "c++|си плюс плюс"}
            ]},
            {"type": "section", "title": "Вопрос 2", "children": [
              {"type": "text", "text": "Как вывести текст в C++?"},
              {"type": "input", "id": "q2", "placeholder": "Впиши свой ответ…", "answer": "cout|std::cout"}
            ]},
            {"type": "section", "title": "Вопрос 3", "children": [
              {"type": "text", "text": "Как называется функция начала программы?"},
              {"type": "input", "id": "q3", "placeholder": "Впиши свой ответ…", "answer": "main"}
            ]},
            {"type": "section", "title": "Вопрос 4", "children": [
              {"type": "text", "text": "Какой тип данных хранит целые числа?"},
              {"type": "choice", "id": "q4", "options": ["int", "float", "char", "bool"], "answer": "int"}
            ]},
            {"type": "section", "title": "Вопрос 5", "children": [
              {"type": "text", "text": "Как называется компилятор GNU для C++?"},
              {"type": "input", "id": "q5", "placeholder": "Впиши свой ответ…", "answer": "g++|gcc"}
            ]},
            {"type": "score", "label": "Твой результат", "when": "checked"},
            {"type": "button", "label": "Проверить", "action": "check", "when": "unchecked"},
            {"type": "button", "label": "Пройти заново", "action": "reset", "style": "outline", "when": "checked"}
          ]
        }
        """.trimIndent(),
    )

    /** A form whose answers go back into the chat as the user's own message. */
    val formUi = Block.Ui(
        """
        {
          "type": "card",
          "children": [
            {"type": "text", "text": "1. Над чем ты сейчас работаешь?", "style": "strong"},
            {"type": "input", "id": "a1", "label": "Над чем ты сейчас работаешь?", "multiline": true},
            {"type": "text", "text": "2. Какой результат нужен к концу дня?", "style": "strong"},
            {"type": "input", "id": "a2", "label": "Какой результат нужен к концу дня?", "multiline": true},
            {"type": "button", "label": "Отправить ответы 💬", "action": "submit",
             "header": "Мои ответы:", "sentLabel": "Отправлено", "when": "always"}
          ]
        }
        """.trimIndent(),
    )

    val svgBlock = Block.Svg(
        caption = "Как телефон связан с devx на компьютере",
        svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="360" height="170" viewBox="0 0 360 170">
              <rect x="10" y="40" width="90" height="90" rx="16" fill="#1E1E1E" stroke="#FFFFFF" stroke-width="2"/>
              <text x="55" y="92" font-size="16" text-anchor="middle" fill="#FFFFFF" font-family="sans-serif">Phone</text>
              <rect x="260" y="40" width="90" height="90" rx="16" fill="#1E1E1E" stroke="#FFFFFF" stroke-width="2"/>
              <text x="305" y="92" font-size="16" text-anchor="middle" fill="#FFFFFF" font-family="sans-serif">devx</text>
              <line x1="104" y1="85" x2="256" y2="85" stroke="#3FB950" stroke-width="3" stroke-dasharray="8 6"/>
              <polygon points="256,85 244,78 244,92" fill="#3FB950"/>
              <text x="180" y="72" font-size="13" text-anchor="middle" fill="#9A9A9A" font-family="sans-serif">P2P · encrypted</text>
            </svg>
        """.trimIndent(),
    )

    val codeBlock = Block.Code(
        language = "bash",
        code = "devx serve --remote\n# scan the QR code in the app",
    )

    val approvalBlock = Block.Approval(command = "rm -rf node_modules && npm install")
}
