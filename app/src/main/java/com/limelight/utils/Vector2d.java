package com.limelight.utils;

/**
 * Mutable 2D vector for analog stick maths.
 *
 * <p>Mutable and reused on purpose: stick processing runs for every axis event, and allocating a
 * vector per event would churn the heap on the input path.
 */
public class Vector2d {
    private float x;
    private float y;
    private double magnitude;
    
    public static final Vector2d ZERO = new Vector2d();
    
    /** Creates a zero vector. */
    public Vector2d() {
        initialize(0, 0);
    }
    
    /** Resets this vector's components, so one instance can be reused across events. */
    public void initialize(float x, float y) {
        this.x = x;
        this.y = y;
        this.magnitude = Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2));
    }
    
    /** @return the vector's length, which is what deadzone tests compare against */
    public double getMagnitude() {
        return magnitude;
    }
    
    
    /** Scales both components in place. */
    public void scalarMultiply(double factor) {
        initialize((float)(x * factor), (float)(y * factor));
    }
    
    
    
    /** @return the X component */
    public float getX() {
        return x;
    }
    
    /** @return the Y component */
    public float getY() {
        return y;
    }
}
