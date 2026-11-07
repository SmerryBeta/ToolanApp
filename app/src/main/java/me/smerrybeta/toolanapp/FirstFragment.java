package me.smerrybeta.toolanapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import me.smerrybeta.toolanapp.databinding.FragmentFirstBinding;

public class FirstFragment extends Fragment {

    // ViewBinding：fragment_first.xml 的绑定类
    private FragmentFirstBinding binding;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        // �?fragment_first.xml 转成 View
        binding = FragmentFirstBinding.inflate(inflater, container, false);

        // 返回这个 Fragment 要显示的界面
        return binding.getRoot();
    }

    // 视图创建完成后调用（适合写点击事件）
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 点击按钮
        binding.textviewFirst.setOnClickListener(v ->
                // 使用 Navigation 组件跳转�?SecondFragment
                NavHostFragment.findNavController(FirstFragment.this)
                        .navigate(R.id.action_FirstFragment_to_SecondFragment)
        );
    }

    // Fragment 的视图被销毁时调用
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 释放 binding，防止内存泄�?        binding = null;
    }
}
