package me.yokeyword.fragmentation;

import android.app.Activity;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.view.View;
import android.view.animation.Animation;

import me.yokeyword.fragmentation.anim.FragmentAnimator;
import me.yokeyword.fragmentation.helper.internal.AnimatorHelper;
import me.yokeyword.fragmentation.helper.internal.ResultRecord;
import me.yokeyword.fragmentation.helper.internal.TransactionRecord;
import me.yokeyword.fragmentation.helper.internal.VisibleDelegate;

public class SupportFragmentDelegate {
    // Animation
    private boolean mEnterAnimDisable;
    boolean mIsSharedElement;
    private FragmentAnimator mFragmentAnimator;
    AnimatorHelper mAnimHelper;
    boolean mLockAnim;

    private boolean mNeedHideSoft;  // 隐藏软键盘
    private Handler mHandler;
    private boolean mFirstCreateView = true;
    private boolean mReplaceMode;
    private boolean mIsHidden = true;   // 用于记录Fragment show/hide 状态
    int mContainerId;   // 该Fragment所处的Container的id

    private TransactionDelegate mTransactionDelegate;
    TransactionRecord mTransactionRecord;
    // SupportVisible
    private VisibleDelegate mVisibleDelegate;
    Bundle mNewBundle;
    private Bundle mSaveInstanceState;

    private ISupportFragment mSupportF;
    private Fragment mFragment;
    protected FragmentActivity _mActivity;
    private ISupportActivity mSupport;

    public SupportFragmentDelegate(ISupportFragment support) {
        if (!(support instanceof Fragment))
            throw new RuntimeException("Must extends Fragment");
        this.mSupportF = support;
        this.mFragment = (Fragment) support;
    }

    /**
     * Add some action when calling start()/startXX()
     */
    public ExtraTransaction extraTransaction() {
        if (mTransactionDelegate == null)
            throw new RuntimeException(mFragment.getClass().getSimpleName() + " not attach!");

        return new ExtraTransaction.ExtraTransactionImpl<>(mSupportF, mTransactionDelegate, false);
    }

    public void onAttach(Activity activity) {
        if (activity instanceof ISupportActivity) {
            this.mSupport = (ISupportActivity) activity;
            this._mActivity = (FragmentActivity) activity;
            mTransactionDelegate = mSupport.getSupportDelegate().getTransactionDelegate();
        } else {
            throw new RuntimeException(activity.getClass().getSimpleName() + " must impl ISupportActivity!");
        }
    }

    public void onCreate(@Nullable Bundle savedInstanceState) {
        getVisibleDelegate().onCreate(savedInstanceState);

        Bundle bundle = mFragment.getArguments();
        if (bundle != null) {
            mEnterAnimDisable = bundle.getBoolean(TransactionDelegate.FRAGMENTATION_ARG_ANIM_DISABLE, false);
            mIsSharedElement = bundle.getBoolean(TransactionDelegate.FRAGMENTATION_ARG_IS_SHARED_ELEMENT, false);
            mContainerId = bundle.getInt(TransactionDelegate.FRAGMENTATION_ARG_CONTAINER);
            mReplaceMode = bundle.getBoolean(TransactionDelegate.FRAGMENTATION_ARG_REPLACE, false);
        }

        if (savedInstanceState == null) {
            getFragmentAnimator();
        } else {
            mSaveInstanceState = savedInstanceState;
            mFragmentAnimator = savedInstanceState.getParcelable(TransactionDelegate.FRAGMENTATION_STATE_SAVE_ANIMATOR);
            mIsHidden = savedInstanceState.getBoolean(TransactionDelegate.FRAGMENTATION_STATE_SAVE_IS_HIDDEN);
        }

        // 解决重叠问题
        processRestoreInstanceState(savedInstanceState);

        initAnim();
    }

    public Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        if ((mSupport.getSupportDelegate().mPopMultipleNoAnim || mLockAnim)) {
            if (transit == FragmentTransaction.TRANSIT_FRAGMENT_CLOSE && enter) {
                return mAnimHelper.getNoneAnimFixed();
            }
            return mAnimHelper.getNoneAnim();
        }
        if (transit == FragmentTransaction.TRANSIT_FRAGMENT_OPEN) {
            if (enter) {
                if (mEnterAnimDisable) return mAnimHelper.getNoneAnim();
                return mAnimHelper.enterAnim;
            } else {
                return mAnimHelper.popExitAnim;
            }
        } else if (transit == FragmentTransaction.TRANSIT_FRAGMENT_CLOSE) {
            return enter ? mAnimHelper.popEnterAnim : mAnimHelper.exitAnim;
        } else {
            if (mIsSharedElement && enter) notifyEnterAnimEnd();

            Animation fixedAnim = mAnimHelper.getViewPagerChildFragmentAnimFixed(mFragment, enter);
            if (fixedAnim != null) return fixedAnim;

            return null;
        }
    }

    public void onSaveInstanceState(Bundle outState) {
        getVisibleDelegate().onSaveInstanceState(outState);
        outState.putParcelable(TransactionDelegate.FRAGMENTATION_STATE_SAVE_ANIMATOR, mFragmentAnimator);
        outState.putBoolean(TransactionDelegate.FRAGMENTATION_STATE_SAVE_IS_HIDDEN, mFragment.isHidden());
    }

    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        getVisibleDelegate().onActivityCreated(savedInstanceState);

        View view = mFragment.getView();
        if (view != null) {
            view.setClickable(true);
            setBackground(view);
        }

        if (savedInstanceState != null || mEnterAnimDisable || (mFragment.getTag() != null
                && mFragment.getTag().startsWith("android:switcher:")) || (mReplaceMode && !mFirstCreateView)) {
            notifyEnterAnimEnd();
        }

        if (mFirstCreateView) {
            mFirstCreateView = false;
        }
    }

    public void onResume() {
        getVisibleDelegate().onResume();
    }

    public void onPause() {
        getVisibleDelegate().onPause();
        if (mNeedHideSoft) {
            hideSoftInput();
        }
    }

    public void onDestroyView() {
        mSupport.getSupportDelegate().mFragmentClickable = true;
        getVisibleDelegate().onDestroyView();
    }

    public void onDestroy() {
        mTransactionDelegate.handleResultRecord(mFragment);
    }

    public void onHiddenChanged(boolean hidden) {
        getVisibleDelegate().onHiddenChanged(hidden);
    }

    public void setUserVisibleHint(boolean isVisibleToUser) {
        getVisibleDelegate().setUserVisibleHint(isVisibleToUser);
    }

    /**
     * 入栈动画 结束时,回调
     */
    public void onEnterAnimationEnd(Bundle savedInstanceState) {
    }

    /**
     * Lazy initial，Called when fragment is first called.
     * <p>
     * 同级下的 懒加载 ＋ ViewPager下的懒加载  的结合回调方法
     */
    public void onLazyInitView(@Nullable Bundle savedInstanceState) {
    }

    /**
     * Called when the fragment is vivible.
     * <p>
     * Is the combination of  [onHiddenChanged() + onResume()/onPause() + setUserVisibleHint()]
     */
    public void onSupportVisible() {
        if (_mActivity != null) {
            mSupport.getSupportDelegate().mFragmentClickable = true;
        }
    }

    /**
     * Called when the fragment is invivible.
     * <p>
     * Is the combination of  [onHiddenChanged() + onResume()/onPause() + setUserVisibleHint()]
     */
    public void onSupportInvisible() {
    }

    /**
     * Return true if the fragment has been supportVisible.
     */
    final public boolean isSupportVisible() {
        return getVisibleDelegate().isSupportVisible();
    }

    /**
     * 设定当前Fragmemt动画,优先级比在SupportActivity里高
     */
    public FragmentAnimator onCreateFragmentAnimator() {
        return mSupport.getFragmentAnimator();
    }

    /**
     * 获取设置的全局动画 copy
     *
     * @return FragmentAnimator
     */
    public FragmentAnimator getFragmentAnimator() {
        if (mSupport == null)
            throw new RuntimeException("Fragment has not been attached to Activity!");

        if (mFragmentAnimator == null) {
            mFragmentAnimator = mSupportF.onCreateFragmentAnimator();
            if (mFragmentAnimator == null) {
                mFragmentAnimator = mSupport.getFragmentAnimator();
            }
        }
        return mFragmentAnimator;
    }

    /**
     * 设置Fragment内的全局动画
     */
    public void setFragmentAnimator(FragmentAnimator fragmentAnimator) {
        this.mFragmentAnimator = fragmentAnimator;
        mAnimHelper.notifyChanged(fragmentAnimator);
    }

    /**
     * 设置Result数据 (通过startForResult)
     */
    public void setFragmentResult(int resultCode, Bundle bundle) {
        Bundle args = mFragment.getArguments();
        if (args == null || !args.containsKey(TransactionDelegate.FRAGMENTATION_ARG_RESULT_RECORD)) {
            return;
        }

        ResultRecord resultRecord = args.getParcelable(TransactionDelegate.FRAGMENTATION_ARG_RESULT_RECORD);
        if (resultRecord != null) {
            resultRecord.resultCode = resultCode;
            resultRecord.resultBundle = bundle;
        }
    }

    /**
     * 接受Result数据 (通过startForResult的返回数据)
     */
    public void onFragmentResult(int requestCode, int resultCode, Bundle data) {
    }

    /**
     * 在start(TargetFragment,LaunchMode)时,启动模式为SingleTask/SingleTop, 回调TargetFragment的该方法
     *
     * @param args 通过上个Fragment的putNewBundle(Bundle newBundle)时传递的数据
     */
    public void onNewBundle(Bundle args) {
    }

    /**
     * 添加NewBundle,用于启动模式为SingleTask/SingleTop时
     */
    public void putNewBundle(Bundle newBundle) {
        this.mNewBundle = newBundle;
    }

    /**
     * 隐藏软键盘
     */
    public void hideSoftInput() {
        SupportHelper.hideSoftInput(mFragment.getView());
    }

    /**
     * 显示软键盘,调用该方法后,会在onPause时自动隐藏软键盘
     */
    public void showSoftInput(View view) {
        if (view == null) return;
        mNeedHideSoft = true;
        SupportHelper.showSoftInput(view);
    }

    /**
     * 按返回键触发,前提是SupportActivity的onBackPressed()方法能被调用
     *
     * @return false则继续向上传递, true则消费掉该事件
     */
    public boolean onBackPressedSupport() {
        return false;
    }

    /**
     * 加载根Fragment, 即Activity内的第一个Fragment 或 Fragment内的第一个子Fragment
     *
     * @param containerId 容器id
     * @param toFragment  目标Fragment
     */
    public void loadRootFragment(int containerId, ISupportFragment toFragment) {
        loadRootFragment(containerId, toFragment, true, false);
    }

    public void loadRootFragment(int containerId, ISupportFragment toFragment, boolean addToBackStack, boolean allowAnim) {
        mTransactionDelegate.loadRootTransaction(getChildFragmentManager(), containerId, toFragment, addToBackStack, allowAnim);
    }

    /**
     * 加载多个同级根Fragment
     *
     * @param containerId 容器id
     * @param toFragments 目标Fragments
     */
    public void loadMultipleRootFragment(int containerId, int showPosition, ISupportFragment... toFragments) {
        mTransactionDelegate.loadMultipleRootTransaction(getChildFragmentManager(), containerId, showPosition, toFragments);
    }

    /**
     * show一个Fragment,hide其他同栈所有Fragment
     * 使用该方法时，要确保同级栈内无多余的Fragment,(只有通过loadMultipleRootFragment()载入的Fragment)
     * <p>
     * 建议使用更明确的{@link #showHideFragment(ISupportFragment, ISupportFragment)}
     *
     * @param showFragment 需要show的Fragment
     */
    public void showHideFragment(ISupportFragment showFragment) {
        showHideFragment(showFragment, null);
    }

    /**
     * show一个Fragment,hide一个Fragment ; 主要用于类似微信主页那种 切换tab的情况
     *
     * @param showFragment 需要show的Fragment
     * @param hideFragment 需要hide的Fragment
     */
    public void showHideFragment(ISupportFragment showFragment, ISupportFragment hideFragment) {
        mTransactionDelegate.showHideFragment(getChildFragmentManager(), showFragment, hideFragment);
    }

    /**
     * 启动目标Fragment
     *
     * @param toFragment 目标Fragment
     */
    public void start(ISupportFragment toFragment) {
        start(toFragment, ISupportFragment.STANDARD);
    }

    public void start(final ISupportFragment toFragment, @ISupportFragment.LaunchMode int launchMode) {
        mTransactionDelegate.dispatchStartTransaction(mFragment.getFragmentManager(), mSupportF, toFragment, 0, launchMode, TransactionDelegate.TYPE_ADD);
    }

    public void startForResult(ISupportFragment toFragment, int requestCode) {
        mTransactionDelegate.dispatchStartTransaction(mFragment.getFragmentManager(), mSupportF, toFragment, requestCode, ISupportFragment.STANDARD, TransactionDelegate.TYPE_ADD_RESULT);
    }

    public void startWithPop(ISupportFragment toFragment) {
        mTransactionDelegate.dispatchStartTransaction(mFragment.getFragmentManager(), mSupportF, toFragment, 0, ISupportFragment.STANDARD, TransactionDelegate.TYPE_ADD_WITH_POP);
    }

    public void replaceFragment(ISupportFragment toFragment, boolean addToBackStack) {
        mTransactionDelegate.dispatchStartTransaction(mFragment.getFragmentManager(), mSupportF, toFragment, 0, ISupportFragment.STANDARD, addToBackStack ? TransactionDelegate.TYPE_REPLACE : TransactionDelegate.TYPE_REPLACE_DONT_BACK);
    }

    /**
     * 启动目标Fragment
     *
     * @param toFragment 目标Fragment
     */
    public void startChild(ISupportFragment toFragment) {
        startChild(toFragment, ISupportFragment.STANDARD);
    }

    public void startChild(final ISupportFragment toFragment, @ISupportFragment.LaunchMode int launchMode) {
        mTransactionDelegate.dispatchStartTransaction(getChildFragmentManager(), getTopFragment(), toFragment, 0, launchMode, TransactionDelegate.TYPE_ADD);
    }

    public void startChildForResult(ISupportFragment toFragment, int requestCode) {
        mTransactionDelegate.dispatchStartTransaction(getChildFragmentManager(), getTopFragment(), toFragment, requestCode, ISupportFragment.STANDARD, TransactionDelegate.TYPE_ADD_RESULT);
    }

    public void startChildWithPop(ISupportFragment toFragment) {
        mTransactionDelegate.dispatchStartTransaction(getChildFragmentManager(), getTopFragment(), toFragment, 0, ISupportFragment.STANDARD, TransactionDelegate.TYPE_ADD_WITH_POP);
    }

    public void replaceChildFragment(ISupportFragment toFragment, boolean addToBackStack) {
        mTransactionDelegate.dispatchStartTransaction(getChildFragmentManager(), getTopFragment(), toFragment, 0, ISupportFragment.STANDARD, addToBackStack ? TransactionDelegate.TYPE_REPLACE : TransactionDelegate.TYPE_REPLACE_DONT_BACK);
    }

    /**
     * 出栈
     */
    public void pop() {
        mTransactionDelegate.back(mFragment.getFragmentManager());
    }

    /**
     * 子栈内 出栈
     */
    public void popChild() {
        mTransactionDelegate.back(getChildFragmentManager());
    }

    /**
     * 出栈到目标fragment
     *
     * @param targetFragmentClass   目标fragment
     * @param includeTargetFragment 是否包含该fragment
     */
    public void popTo(Class<?> targetFragmentClass, boolean includeTargetFragment) {
        popTo(targetFragmentClass, includeTargetFragment, null);
    }

    /**
     * 用于出栈后,立刻进行FragmentTransaction操作
     */
    public void popTo(Class<?> targetFragmentClass, boolean includeTargetFragment, Runnable afterPopTransactionRunnable) {
        popTo(targetFragmentClass, includeTargetFragment, afterPopTransactionRunnable, 0);
    }

    public void popTo(Class<?> targetFragmentClass, boolean includeTargetFragment, Runnable afterPopTransactionRunnable, int popAnim) {
        mTransactionDelegate.popTo(targetFragmentClass.getName(), includeTargetFragment, afterPopTransactionRunnable, mFragment.getFragmentManager(), popAnim);
    }

    /**
     * 子栈内
     */
    public void popToChild(Class<?> targetFragmentClass, boolean includeTargetFragment) {
        popToChild(targetFragmentClass, includeTargetFragment, null);
    }

    public void popToChild(Class<?> targetFragmentClass, boolean includeTargetFragment, Runnable afterPopTransactionRunnable) {
        popToChild(targetFragmentClass, includeTargetFragment, afterPopTransactionRunnable, 0);
    }

    public void popToChild(Class<?> targetFragmentClass, boolean includeTargetFragment, Runnable afterPopTransactionRunnable, int popAnim) {
        mTransactionDelegate.popTo(targetFragmentClass.getName(), includeTargetFragment, afterPopTransactionRunnable, getChildFragmentManager(), popAnim);
    }

    private FragmentManager getChildFragmentManager() {
        return mFragment.getChildFragmentManager();
    }

    private ISupportFragment getTopFragment() {
        return SupportHelper.getTopFragment(getChildFragmentManager());
    }

    private void processRestoreInstanceState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            FragmentTransaction ft = mFragment.getFragmentManager().beginTransaction();
            if (mIsHidden) {
                ft.hide(mFragment);
            } else {
                ft.show(mFragment);
            }
            ft.commit();
        }
    }

    private void initAnim() {
        mAnimHelper = new AnimatorHelper(_mActivity.getApplicationContext(), mFragmentAnimator);
        // 监听入栈动画结束(1.为了防抖动; 2.为了Fragmentation的回调所用)
        mAnimHelper.enterAnim.setAnimationListener(new Animation.AnimationListener() {

            @Override
            public void onAnimationStart(Animation animation) {
                mSupport.getSupportDelegate().mFragmentClickable = false;  // 开启防抖动
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                notifyEnterAnimEnd();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
    }

    public void setBackground(View view) {
        if ((mFragment.getTag() != null && mFragment.getTag().startsWith("android:switcher:")) ||
                mEnterAnimDisable ||
                view.getBackground() != null) {
            return;
        }

        int defaultBg = mSupport.getSupportDelegate().getDefaultFragmentBackground();
        if (defaultBg == 0) {
            int background = getWindowBackground();
            view.setBackgroundResource(background);
        } else {
            view.setBackgroundResource(defaultBg);
        }
    }

    private int getWindowBackground() {
        TypedArray a = _mActivity.getTheme().obtainStyledAttributes(new int[]{
                android.R.attr.windowBackground
        });
        int background = a.getResourceId(0, 0);
        a.recycle();
        return background;
    }

    private void notifyEnterAnimEnd() {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                mSupportF.onEnterAnimationEnd(mSaveInstanceState);
            }
        });
        mSupport.getSupportDelegate().mFragmentClickable = true;
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        return mHandler;
    }

    public VisibleDelegate getVisibleDelegate() {
        if (mVisibleDelegate == null) {
            mVisibleDelegate = new VisibleDelegate(mSupportF);
        }
        return mVisibleDelegate;
    }
}