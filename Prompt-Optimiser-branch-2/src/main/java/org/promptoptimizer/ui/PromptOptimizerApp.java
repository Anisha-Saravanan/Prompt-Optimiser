package org.promptoptimizer.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.text.Text;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.promptoptimizer.PipelineDemo;

/** JavaFX chat interface for the existing prompt optimization workflow. */
public final class PromptOptimizerApp extends Application {
    private VBox messages;
    private ScrollPane chatScroll;
    private TextArea promptInput;
    private Button sendButton;

    @Override
    public void start(Stage stage) {
        messages = new VBox(16);
        chatScroll = new ScrollPane(messages);
        promptInput = new TextArea();
        sendButton = new Button("\u2191");

        Label title = new Label("Prompt Optimizer");
        title.getStyleClass().add("title");
        Label subtitle = new Label("Improve your prompts with AI.");
        subtitle.getStyleClass().add("subtitle");

        VBox header = new VBox(4, title, subtitle);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);

        messages.getStyleClass().add("messages");
        messages.setFillWidth(true);
        messages.setPadding(new Insets(24));
        messages.heightProperty().addListener((observable, oldValue, newValue) ->
                chatScroll.setVvalue(1.0));
        chatScroll.getStyleClass().add("chat-scroll");
        chatScroll.setFitToWidth(true);
        chatScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chatScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);

        promptInput.setPromptText("Enter your prompt...");
        promptInput.setWrapText(true);
        promptInput.setPrefRowCount(3);
        promptInput.getStyleClass().add("prompt-input");
        promptInput.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()
                    && !event.isControlDown() && !event.isAltDown()) {
                event.consume();
                submitPrompt();
            }
        });

        sendButton.getStyleClass().add("send-button");
        sendButton.setAccessibleText("Send prompt");
        sendButton.setOnAction(event -> submitPrompt());

        HBox composer = new HBox(12, promptInput, sendButton);
        composer.getStyleClass().add("composer");
        composer.setAlignment(Pos.BOTTOM_CENTER);
        HBox.setHgrow(promptInput, Priority.ALWAYS);

        VBox root = new VBox(0, header, chatScroll, composer);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 1000, 760);
        scene.getStylesheets().add(getClass().getResource("chat.css").toExternalForm());
        stage.setTitle("Prompt Optimizer");
        stage.setMinWidth(560);
        stage.setMinHeight(480);
        stage.setScene(scene);
        stage.show();
    }

    private void submitPrompt() {
        String prompt = promptInput.getText();
        if (prompt == null || prompt.isBlank() || sendButton.isDisabled()) {
            return;
        }

        promptInput.clear();
        addMessage(prompt, true);
        TextArea response = addMessage("Optimizing...", false);
        promptInput.setDisable(true);
        sendButton.setDisable(true);

        Task<String> optimizationTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                return PipelineDemo.optimizePrompt(prompt);
            }
        };
        optimizationTask.setOnSucceeded(event -> {
            response.setText(optimizationTask.getValue());
            setProcessing(false);
        });
        optimizationTask.setOnFailed(event -> {
            response.setText("Unable to optimize this prompt. Please check the application setup or try again.");
            promptInput.setText(prompt);
            setProcessing(false);
        });
        Thread worker = new Thread(optimizationTask, "prompt-optimization");
        worker.setDaemon(true);
        worker.start();
    }

    private TextArea addMessage(String text, boolean user) {
        TextArea bubble = new TextArea(text);
        bubble.setEditable(false);
        bubble.setWrapText(true);
        bubble.setFocusTraversable(true);
        bubble.setMaxWidth(780);
        bubble.maxWidthProperty().bind(chatScroll.widthProperty().multiply(0.72));
        bubble.getStyleClass().addAll("message-bubble", user ? "user-bubble" : "assistant-bubble");
        bubble.textProperty().addListener((observable, oldValue, newValue) -> resizeMessage(bubble));
        bubble.widthProperty().addListener((observable, oldValue, newValue) -> resizeMessage(bubble));
        Platform.runLater(() -> resizeMessage(bubble));

        HBox row = new HBox(bubble);
        row.setAlignment(user ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.getStyleClass().add("message-row");
        messages.getChildren().add(row);
        return bubble;
    }

    private void resizeMessage(TextArea bubble) {
        if (bubble.getWidth() <= 0) {
            return;
        }
        Text measurement = new Text(bubble.getText());
        measurement.setFont(bubble.getFont());
        measurement.setWrappingWidth(Math.max(80, bubble.getWidth() - 42));
        double contentHeight = measurement.getLayoutBounds().getHeight();
        int lines = Math.max(1, bubble.getText().split("\n", -1).length);
        bubble.setPrefHeight(Math.max(52, contentHeight + lines * 4 + 38));
    }

    private void setProcessing(boolean processing) {
        promptInput.setDisable(processing);
        sendButton.setDisable(processing);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
