package com.simonramstedt.yoke;

import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;


public class JoystickView extends View {

    public interface OnMoveListener {
        void onMove(float[] pos);
    }

    private boolean moving = false;

    private Paint mButtonRect;
    private Paint mBorderRect;
    private Paint mBackground;

    private int mPosX = 0;
    private int mPosY = 0;
    private int mCenterX = 0;
    private int mCenterY = 0;

    private boolean mFixed = false;

    private int mButtonRadius;
    private int mBorderRadius;

    private float mBackgroundRadius;

    private OnMoveListener mOnMoveListener;

    public JoystickView(Context context) {
        this(context, null);
    }


    public JoystickView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs);
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mButtonRect = new Paint();
        mButtonRect.setAntiAlias(true);
        mButtonRect.setColor(Color.BLACK);
        mButtonRect.setStyle(Paint.Style.FILL);

        mBorderRect = new Paint();
        mBorderRect.setAntiAlias(true);
        mBorderRect.setColor(getResources().getColor(android.R.color.holo_blue_bright));
        mBorderRect.setStyle(Paint.Style.STROKE);
        mBorderRect.setStrokeWidth(sp(10));

        mBackground = new Paint();
        mBackground.setAntiAlias(true);
        mBackground.setColor(getResources().getColor(android.R.color.holo_blue_light));
        mBackground.setStyle(Paint.Style.FILL);
    }

    private int sp(float sp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(mCenterX -mBackgroundRadius, mCenterY -mBackgroundRadius, mCenterX +mBackgroundRadius, mCenterY +mBackgroundRadius, mBackground);

        canvas.drawRect(mCenterX -mBorderRadius, mCenterY -mBorderRadius, mCenterX +mBorderRadius, mCenterY +mBorderRadius, mBorderRect);

        canvas.drawCircle(mPosX, mPosY, mButtonRadius, mButtonRect);
    }

    @Override
    protected void onSizeChanged(int w, int h, int wOld, int hOld) {
        super.onSizeChanged(w, h, wOld, hOld);

        mCenterX = mPosX = w / 2;
        mCenterY = mPosY = h / 2;

        int r = Math.min(w, h) / 2;
        mButtonRadius = (int) (r * 0.25f);
        mBorderRadius = (int) (r * 0.75f);
        mBackgroundRadius = mBorderRadius - (mBorderRect.getStrokeWidth() / 2);

    }

    public void setFixed(boolean b){
        mFixed = b;
        if(!b)
            recenter();
    }

    public float[] getRelPos(){
        float[] p = {(mPosX - mCenterX) / (float) mBorderRadius, (mPosY - mCenterY) / (float) mBorderRadius};
        return p;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? 150 : MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ? 150 : MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    public void recenter(){
        mPosX = mCenterX;
        mPosY = mCenterY;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mPosX = Math.max(-mBorderRadius, Math.min(mBorderRadius, (int) event.getX()-mCenterX)) + mCenterX;
        mPosY = Math.max(-mBorderRadius, Math.min(mBorderRadius, (int) event.getY()-mCenterY)) + mCenterY;

        if (event.getAction() == MotionEvent.ACTION_UP) {
            moving = false;
            if (!mFixed)
                recenter();

        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            moving = true;
        }

        if (moving && mOnMoveListener != null)
            mOnMoveListener.onMove(getRelPos());

        invalidate();

        return true;
    }

    public void setOnMoveListener(OnMoveListener l) {
        mOnMoveListener = l;
    }
}