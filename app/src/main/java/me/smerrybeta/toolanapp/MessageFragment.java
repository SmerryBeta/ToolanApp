package me.smerrybeta.toolanapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import me.smerrybeta.toolanapp.databinding.FragmentSecondBinding;


public class MessageFragment extends Fragment {

    // fragment_second.xml �?ViewBinding
    private FragmentSecondBinding binding;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        // 加载布局
        binding = FragmentSecondBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    // 页面创建完成
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 点击按钮，跳�?FirstFragment
        binding.buttonSecond.setOnClickListener(v ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_MessageFragment_to_FirstFragment)
        );
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
