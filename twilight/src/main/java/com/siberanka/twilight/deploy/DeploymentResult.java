package com.siberanka.twilight.deploy;

import java.nio.file.Path;
import java.util.List;

public record DeploymentResult(boolean success, Path geyserDirectory, Path snapshot, List<String> deployedFiles, String message) {}
