package com.msp1974.vacompanion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.msp1974.vacompanion.settings.APPConfig

abstract class ViewModelBase(application: Application): AndroidViewModel(application) {

    protected val app = application
}