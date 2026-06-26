package io.infrahack.elevator.model;

import io.infrahack.elevator.enums.ButtonType;

public class Button {
    private final String label;
    private final ButtonType type;
    private boolean lit; // Light the button when pressed

    public Button(String label, ButtonType type) {
        this.label = label;
        this.type = type;
    }

    public void press() {
        lit = true;
    }

    public  void clear() {
        lit = false;
    }

    public String label() {
        return label;
    }

    public ButtonType type() {
        return type;
    }

    public boolean isLit() {
        return lit;
    }
}
